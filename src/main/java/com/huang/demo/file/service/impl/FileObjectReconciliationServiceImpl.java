package com.huang.demo.file.service.impl;

import com.huang.demo.common.compensation.domain.model.CompensationFailureType;
import com.huang.demo.common.compensation.service.CompensationService;
import com.huang.demo.common.lock.DistributedLockService;
import com.huang.demo.file.config.FileCenterProperties;
import com.huang.demo.file.domain.entity.FileRecord;
import com.huang.demo.file.domain.entity.FileUploadTask;
import com.huang.demo.file.domain.model.FileReconciliationResult;
import com.huang.demo.file.repository.FileRecordMapper;
import com.huang.demo.file.repository.FileUploadTaskMapper;
import com.huang.demo.file.service.FileObjectReconciliationService;
import com.huang.demo.file.service.FileObjectStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class FileObjectReconciliationServiceImpl implements FileObjectReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(FileObjectReconciliationServiceImpl.class);

    private final FileRecordMapper fileRecordMapper;
    private final FileUploadTaskMapper fileUploadTaskMapper;
    private final FileObjectStorageService fileObjectStorageService;
    private final FileCenterProperties properties;
    private final CompensationService compensationService;
    private final DistributedLockService distributedLockService;

    @Autowired
    public FileObjectReconciliationServiceImpl(FileRecordMapper fileRecordMapper,
                                               FileUploadTaskMapper fileUploadTaskMapper,
                                               FileObjectStorageService fileObjectStorageService,
                                               FileCenterProperties properties,
                                               CompensationService compensationService,
                                               DistributedLockService distributedLockService) {
        this.fileRecordMapper = fileRecordMapper;
        this.fileUploadTaskMapper = fileUploadTaskMapper;
        this.fileObjectStorageService = fileObjectStorageService;
        this.properties = properties;
        this.compensationService = compensationService;
        this.distributedLockService = distributedLockService;
    }

    @Scheduled(
            initialDelayString = "${app.file.reconciliation-initial-delay-millis:600000}",
            fixedDelayString = "${app.file.reconciliation-fixed-delay-millis:3600000}"
    )
    public void scheduledReconcile() {
        if (!properties.isReconciliationEnabled()) {
            return;
        }
        reconcileOnceWithLock();
    }

    @Override
    public FileReconciliationResult reconcileOnceWithLock() {
        String lockKey = normalizeLockKey(properties.getReconciliationLockKey());
        String ownerToken = UUID.randomUUID().toString().replace("-", "");
        if (!distributedLockService.tryLock(
                lockKey,
                ownerToken,
                Duration.ofSeconds(normalizeLockTtlSeconds(properties.getReconciliationLockTtlSeconds())))) {
            log.info("file object reconciliation skipped, lock is held by another worker");
            return FileReconciliationResult.empty();
        }
        try {
            return reconcileOnce();
        } finally {
            distributedLockService.release(lockKey, ownerToken);
        }
    }

    @Override
    public FileReconciliationResult reconcileOnce() {
        long startMillis = System.currentTimeMillis();
        int batchSize = normalizeBatchSize(properties.getReconciliationBatchSize());
        Set<String> knownGeneralObjectKeys = new HashSet<String>();
        Set<String> knownMultipartPrefixes = new HashSet<String>();

        long fileRecordsChecked = checkFileRecords(batchSize, knownGeneralObjectKeys);
        long uploadTasksChecked = collectUploadTaskObjects(
                batchSize, knownGeneralObjectKeys, knownMultipartPrefixes);
        ObjectListing generalObjects = listObjectsForReconciliation(properties.getObjectPrefix());
        ObjectListing multipartObjects = listObjectsForReconciliation(properties.getMultipartObjectPrefix());
        ReconciliationCounters missingCounters = generalObjects.success
                ? markMissingNormalFiles(batchSize, generalObjects.objectKeys)
                : new ReconciliationCounters();
        ReconciliationCounters staleCounters = checkStaleUploadTasks(batchSize);
        ReconciliationCounters orphanCounters = checkOrphanObjects(
                generalObjects, multipartObjects, knownGeneralObjectKeys, knownMultipartPrefixes);

        FileReconciliationResult result = FileReconciliationResult.builder()
                .fileRecordsChecked(fileRecordsChecked)
                .missingFileRecords(missingCounters.missingFileRecords)
                .uploadTasksChecked(uploadTasksChecked)
                .expiredUploadTasks(staleCounters.expiredUploadTasks)
                .orphanObjects(orphanCounters.orphanObjects + staleCounters.orphanObjects)
                .cleanupFailures(staleCounters.cleanupFailures
                        + (generalObjects.success ? 0L : 1L)
                        + (multipartObjects.success ? 0L : 1L))
                .compensationRecords(missingCounters.compensationRecords
                        + staleCounters.compensationRecords
                        + orphanCounters.compensationRecords
                        + generalObjects.compensationRecords
                        + multipartObjects.compensationRecords)
                .build();
        log.info("file object reconciliation finished, fileRecordsChecked={}, missingFileRecords={}, " +
                        "uploadTasksChecked={}, expiredUploadTasks={}, orphanObjects={}, cleanupFailures={}, " +
                        "compensationRecords={}, elapsedMs={}",
                result.getFileRecordsChecked(), result.getMissingFileRecords(),
                result.getUploadTasksChecked(), result.getExpiredUploadTasks(),
                result.getOrphanObjects(), result.getCleanupFailures(),
                result.getCompensationRecords(), System.currentTimeMillis() - startMillis);
        return result;
    }

    private long checkFileRecords(int batchSize, Set<String> knownObjectKeys) {
        long lastId = 0L;
        long checked = 0L;
        while (true) {
            List<FileRecord> records = safeFileRecords(fileRecordMapper.listAllAfterId(lastId, batchSize));
            if (records.isEmpty()) {
                return checked;
            }
            for (FileRecord record : records) {
                checked++;
                if (record != null && hasText(record.getObjectKey())) {
                    knownObjectKeys.add(record.getObjectKey());
                }
                lastId = maxId(lastId, record == null ? null : record.getId());
            }
            if (records.size() < batchSize) {
                return checked;
            }
        }
    }

    private ReconciliationCounters markMissingNormalFiles(int batchSize, Set<String> actualObjectKeys) {
        ReconciliationCounters counters = new ReconciliationCounters();
        long lastId = 0L;
        while (true) {
            List<FileRecord> records = safeFileRecords(
                    fileRecordMapper.listNormalAfterId(lastId, batchSize));
            if (records.isEmpty()) {
                return counters;
            }
            for (FileRecord record : records) {
                if (record != null && hasText(record.getObjectKey())
                        && !actualObjectKeys.contains(record.getObjectKey())) {
                    int updated = fileRecordMapper.markDeleted(record.getOwnerId(), record.getFileId());
                    if (updated > 0) {
                        counters.missingFileRecords++;
                    }
                    counters.compensationRecords += recordCompensation(
                            "FILE",
                            record.getFileId(),
                            CompensationFailureType.OBJECT_MISSING.name(),
                            "objectKey=" + record.getObjectKey());
                    log.warn("file object missing during reconciliation, fileId={}, objectKey={}",
                            record.getFileId(), record.getObjectKey());
                }
                lastId = maxId(lastId, record == null ? null : record.getId());
            }
            if (records.size() < batchSize) {
                return counters;
            }
        }
    }

    private long collectUploadTaskObjects(int batchSize,
                                          Set<String> knownObjectKeys,
                                          Set<String> knownMultipartPrefixes) {
        long lastId = 0L;
        long checked = 0L;
        while (true) {
            List<FileUploadTask> tasks = safeUploadTasks(
                    fileUploadTaskMapper.listAllAfterId(lastId, batchSize));
            if (tasks.isEmpty()) {
                return checked;
            }
            for (FileUploadTask task : tasks) {
                checked++;
                if (task != null) {
                    if (hasText(task.getObjectKey())) {
                        knownObjectKeys.add(task.getObjectKey());
                    }
                    if (hasText(task.getPartObjectPrefix())) {
                        knownMultipartPrefixes.add(normalizePrefix(task.getPartObjectPrefix()));
                    }
                    lastId = maxId(lastId, task.getId());
                }
            }
            if (tasks.size() < batchSize) {
                return checked;
            }
        }
    }

    private ReconciliationCounters checkStaleUploadTasks(int batchSize) {
        ReconciliationCounters counters = new ReconciliationCounters();
        LocalDateTime staleBefore = LocalDateTime.now()
                .minusHours(normalizeStaleUploadHours(properties.getReconciliationUploadStaleHours()));
        long lastId = 0L;
        while (true) {
            List<FileUploadTask> tasks = safeUploadTasks(
                    fileUploadTaskMapper.listUploadingAfterId(lastId, batchSize));
            if (tasks.isEmpty()) {
                return counters;
            }
            for (FileUploadTask task : tasks) {
                if (task == null) {
                    continue;
                }
                lastId = maxId(lastId, task.getId());
                if (task.getCreatedAt() == null || task.getCreatedAt().isAfter(staleBefore)) {
                    continue;
                }
                counters.expiredUploadTasks++;
                List<String> partKeys;
                try {
                    partKeys = listObjectKeys(task.getPartObjectPrefix());
                } catch (RuntimeException ex) {
                    counters.cleanupFailures++;
                    counters.compensationRecords += recordCompensation(
                            "FILE_UPLOAD",
                            task.getUploadId(),
                            CompensationFailureType.CLEANUP_OBJECT_FAILED.name(),
                            "objectKey=" + task.getPartObjectPrefix() + ",error=" + safeErrorMessage(ex));
                    continue;
                }
                boolean mainObjectExists = objectExists(task.getObjectKey());
                if (mainObjectExists || !partKeys.isEmpty()) {
                    counters.orphanObjects++;
                    counters.compensationRecords += recordCompensation(
                            "FILE_UPLOAD",
                            task.getUploadId(),
                            CompensationFailureType.ORPHAN_OBJECT.name(),
                            "objectKey=" + task.getObjectKey() + ",partCount=" + partKeys.size());
                }
            }
            if (tasks.size() < batchSize) {
                return counters;
            }
        }
    }

    private ReconciliationCounters checkOrphanObjects(ObjectListing generalObjects,
                                                      ObjectListing multipartObjects,
                                                      Set<String> knownGeneralObjectKeys,
                                                      Set<String> knownMultipartPrefixes) {
        ReconciliationCounters counters = new ReconciliationCounters();
        if (generalObjects.success) {
            counters.add(checkOrphanObjectKeys(
                    generalObjects.objectKeys, knownGeneralObjectKeys,
                    Collections.<String>emptySet(), "FILE", properties.getObjectPrefix()));
        }
        if (multipartObjects.success) {
            counters.add(checkOrphanObjectKeys(
                    multipartObjects.objectKeys, Collections.<String>emptySet(),
                    knownMultipartPrefixes, "FILE_UPLOAD", properties.getMultipartObjectPrefix()));
        }
        return counters;
    }

    private ReconciliationCounters checkOrphanObjectKeys(Set<String> actualObjectKeys,
                                                         Set<String> knownObjectKeys,
                                                         Set<String> knownPrefixes,
                                                         String bizType,
                                                         String prefix) {
        ReconciliationCounters counters = new ReconciliationCounters();
        String normalizedPrefix = normalizePrefix(prefix);
        for (String objectKey : actualObjectKeys) {
            if (knownObjectKeys.contains(objectKey)
                    || isUnderKnownPrefix(objectKey, knownPrefixes)) {
                continue;
            }
            counters.orphanObjects++;
            counters.compensationRecords += recordCompensation(
                    bizType,
                    objectKey,
                    CompensationFailureType.ORPHAN_OBJECT.name(),
                    "objectKey=" + objectKey + ",prefix=" + normalizedPrefix);
        }
        return counters;
    }

    private ObjectListing listObjectsForReconciliation(String prefix) {
        String normalizedPrefix = normalizePrefix(prefix);
        if (!hasText(normalizedPrefix)) {
            return ObjectListing.success(Collections.<String>emptySet());
        }
        try {
            return ObjectListing.success(new HashSet<String>(listObjectKeys(normalizedPrefix)));
        } catch (RuntimeException ex) {
            long compensationRecords = recordCompensation(
                    "FILE_STORAGE",
                    normalizedPrefix,
                    CompensationFailureType.CLEANUP_OBJECT_FAILED.name(),
                    "objectPrefix=" + normalizedPrefix + ",error=" + safeErrorMessage(ex));
            log.warn("file object reconciliation list failed, objectPrefix={}", normalizedPrefix, ex);
            return ObjectListing.failed(compensationRecords);
        }
    }

    private boolean objectExists(String objectKey) {
        try {
            fileObjectStorageService.statObject(objectKey);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private List<String> listObjectKeys(String prefix) {
        if (!hasText(prefix)) {
            return Collections.emptyList();
        }
        List<String> objectKeys = fileObjectStorageService.listObjectKeys(prefix);
        return objectKeys == null ? Collections.<String>emptyList() : objectKeys;
    }

    private long recordCompensation(String bizType,
                                    String bizId,
                                    String failureType,
                                    String payload) {
        return compensationService.recordPending(bizType, bizId, failureType, payload) == null ? 0L : 1L;
    }

    private List<FileRecord> safeFileRecords(List<FileRecord> records) {
        return records == null ? Collections.<FileRecord>emptyList() : records;
    }

    private List<FileUploadTask> safeUploadTasks(List<FileUploadTask> tasks) {
        return tasks == null ? Collections.<FileUploadTask>emptyList() : tasks;
    }

    private long maxId(long current, Long candidate) {
        return candidate == null ? current : Math.max(current, candidate);
    }

    private boolean isUnderKnownPrefix(String objectKey, Set<String> prefixes) {
        if (!hasText(objectKey) || prefixes == null) {
            return false;
        }
        for (String prefix : prefixes) {
            if (objectKey.equals(prefix) || objectKey.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    private String normalizePrefix(String prefix) {
        if (!hasText(prefix)) {
            return null;
        }
        String normalized = prefix.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String normalizeLockKey(String lockKey) {
        return hasText(lockKey) ? lockKey.trim() : "file:reconciliation:lock";
    }

    private int normalizeBatchSize(int batchSize) {
        if (batchSize <= 0) {
            return 100;
        }
        return Math.min(batchSize, 1000);
    }

    private int normalizeStaleUploadHours(int hours) {
        return Math.max(1, hours);
    }

    private int normalizeLockTtlSeconds(int seconds) {
        return Math.max(60, seconds);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String safeErrorMessage(RuntimeException ex) {
        if (ex == null || ex.getMessage() == null) {
            return "unknown";
        }
        String message = ex.getMessage().replace('\n', ' ').replace('\r', ' ');
        return message.length() > 512 ? message.substring(0, 512) : message;
    }

    private static class ReconciliationCounters {
        private long missingFileRecords;
        private long expiredUploadTasks;
        private long orphanObjects;
        private long cleanupFailures;
        private long compensationRecords;

        private void add(ReconciliationCounters other) {
            if (other == null) {
                return;
            }
            this.missingFileRecords += other.missingFileRecords;
            this.expiredUploadTasks += other.expiredUploadTasks;
            this.orphanObjects += other.orphanObjects;
            this.cleanupFailures += other.cleanupFailures;
            this.compensationRecords += other.compensationRecords;
        }
    }

    private static class ObjectListing {
        private final boolean success;
        private final Set<String> objectKeys;
        private final long compensationRecords;

        private ObjectListing(boolean success, Set<String> objectKeys, long compensationRecords) {
            this.success = success;
            this.objectKeys = objectKeys;
            this.compensationRecords = compensationRecords;
        }

        private static ObjectListing success(Set<String> objectKeys) {
            return new ObjectListing(true, objectKeys, 0L);
        }

        private static ObjectListing failed(long compensationRecords) {
            return new ObjectListing(false, Collections.<String>emptySet(), compensationRecords);
        }
    }
}
