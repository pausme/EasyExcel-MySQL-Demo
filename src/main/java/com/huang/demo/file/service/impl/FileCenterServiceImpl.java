package com.huang.demo.file.service.impl;

import com.huang.demo.common.compensation.domain.model.CompensationFailureType;
import com.huang.demo.common.compensation.service.CompensationService;
import com.huang.demo.file.api.dto.FilePageQueryRequest;
import com.huang.demo.file.api.dto.FilePageResponse;
import com.huang.demo.file.api.dto.FileResponse;
import com.huang.demo.file.api.dto.DirectUploadInitRequest;
import com.huang.demo.file.api.dto.DirectUploadInitResponse;
import com.huang.demo.file.api.dto.InstantUploadCheckRequest;
import com.huang.demo.file.api.dto.InstantUploadCheckResponse;
import com.huang.demo.file.api.dto.MultipartPartsResponse;
import com.huang.demo.file.api.dto.MultipartUploadInitRequest;
import com.huang.demo.file.api.dto.MultipartUploadInitResponse;
import com.huang.demo.file.api.dto.PartUploadUrlResponse;
import com.huang.demo.file.config.FileCenterProperties;
import com.huang.demo.file.domain.entity.FileRecord;
import com.huang.demo.file.domain.entity.FileUploadTask;
import com.huang.demo.file.domain.model.FileStatus;
import com.huang.demo.file.domain.model.FileUploadStatus;
import com.huang.demo.file.domain.model.FileUploadType;
import com.huang.demo.file.domain.model.StorageType;
import com.huang.demo.file.domain.model.StoredFile;
import com.huang.demo.file.domain.model.StoredObject;
import com.huang.demo.file.repository.FileRecordMapper;
import com.huang.demo.file.repository.FileUploadTaskMapper;
import com.huang.demo.file.service.FileCenterService;
import com.huang.demo.file.service.FileObjectStorageService;
import com.huang.demo.file.service.FileSecurityScanner;
import com.huang.demo.common.lock.DistributedLockService;
import com.huang.demo.security.domain.CurrentUser;
import com.huang.demo.security.domain.UserContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

@Service
public class FileCenterServiceImpl implements FileCenterService {

    private static final Logger log = LoggerFactory.getLogger(FileCenterServiceImpl.class);
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final long MIN_MULTIPART_PART_SIZE = 5L * 1024L * 1024L;
    private static final int PART_STAT_MAX_ATTEMPTS = 3;
    private static final long PART_STAT_RETRY_INTERVAL_MS = 200L;
    private static final DistributedLockService NOOP_LOCK_SERVICE = new DistributedLockService() {
        @Override
        public boolean tryLock(String lockKey, String ownerToken, java.time.Duration ttl) {
            return true;
        }

        @Override
        public boolean release(String lockKey, String ownerToken) {
            return true;
        }
    };

    private final FileRecordMapper fileRecordMapper;
    private final FileUploadTaskMapper fileUploadTaskMapper;
    private final FileObjectStorageService fileObjectStorageService;
    private final FileSecurityScanner fileSecurityScanner;
    private final FileCenterProperties properties;
    private final DistributedLockService distributedLockService;
    private final CompensationService compensationService;

    @Autowired
    public FileCenterServiceImpl(FileRecordMapper fileRecordMapper,
                                 FileUploadTaskMapper fileUploadTaskMapper,
                                 FileObjectStorageService fileObjectStorageService,
                                 FileSecurityScanner fileSecurityScanner,
                                 FileCenterProperties properties,
                                 DistributedLockService distributedLockService,
                                 CompensationService compensationService) {
        this.fileRecordMapper = fileRecordMapper;
        this.fileUploadTaskMapper = fileUploadTaskMapper;
        this.fileObjectStorageService = fileObjectStorageService;
        this.fileSecurityScanner = fileSecurityScanner;
        this.properties = properties;
        this.distributedLockService = distributedLockService;
        this.compensationService = compensationService;
    }

    public FileCenterServiceImpl(FileRecordMapper fileRecordMapper,
                                 FileUploadTaskMapper fileUploadTaskMapper,
                                 FileObjectStorageService fileObjectStorageService,
                                 FileSecurityScanner fileSecurityScanner,
                                 FileCenterProperties properties) {
        this(fileRecordMapper, fileUploadTaskMapper, fileObjectStorageService,
                fileSecurityScanner, properties, NOOP_LOCK_SERVICE, CompensationService.noop());
    }

    public FileCenterServiceImpl(FileRecordMapper fileRecordMapper,
                                 FileUploadTaskMapper fileUploadTaskMapper,
                                 FileObjectStorageService fileObjectStorageService,
                                 FileSecurityScanner fileSecurityScanner,
                                 FileCenterProperties properties,
                                 DistributedLockService distributedLockService) {
        this(fileRecordMapper, fileUploadTaskMapper, fileObjectStorageService,
                fileSecurityScanner, properties, distributedLockService, CompensationService.noop());
    }

    @PostConstruct
    public void init() {
        if (!properties.isInitEnabled()) {
            log.info("file center database initialization skipped");
            return;
        }
        fileRecordMapper.createTableIfAbsent();
        fileUploadTaskMapper.createTableIfAbsent();
        log.info("file center initialized");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileRecord upload(MultipartFile file) throws IOException {
        validateUploadFile(file);
        validateQuotaForNewFile(file.getSize(), false, false);
        String fileId = UUID.randomUUID().toString().replace("-", "");
        String originalName = normalizeOriginalName(file.getOriginalFilename());
        String fileExt = resolveFileExt(originalName);
        String objectKey = buildObjectKey(fileId, fileExt);
        StoredFile storedFile = null;

        try {
            validateUploadMetadata(originalName, file.getContentType());
            try (java.io.InputStream scanStream = file.getInputStream()) {
                fileSecurityScanner.scan(scanStream, originalName, file.getContentType(), file.getSize());
            }
            storedFile = fileObjectStorageService.upload(
                    file.getInputStream(), file.getSize(), objectKey, file.getContentType());
            FileRecord record = buildFileRecord(fileId, originalName, fileExt, file.getContentType(), storedFile);
            fileRecordMapper.insert(record);
            log.info("file uploaded, fileId={}, originalName={}, size={}",
                    record.getFileId(), record.getOriginalName(), record.getFileSize());
            return record;
        } catch (RuntimeException ex) {
            if (storedFile != null) {
                fileObjectStorageService.deleteQuietly(storedFile.getObjectKey());
            }
            throw ex;
        }
    }

    @Override
    public InstantUploadCheckResponse instantCheck(InstantUploadCheckRequest request) {
        String fileMd5 = normalizeFileMd5(request == null ? null : request.getFileMd5());
        long fileSize = normalizePositiveFileSize(request == null ? null : request.getFileSize());
        Optional<FileRecord> recordOptional = fileRecordMapper.findNormalByMd5AndSize(currentOwnerId(), fileMd5, fileSize);
        return InstantUploadCheckResponse.builder()
                .exists(recordOptional.isPresent())
                .file(recordOptional.map(FileResponse::from).orElse(null))
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DirectUploadInitResponse initDirectUpload(DirectUploadInitRequest request) {
        validateDirectUploadInitRequest(request);
        String fileMd5 = normalizeFileMd5(request.getFileMd5());
        long fileSize = normalizePositiveFileSize(request.getFileSize());
        validateUploadMetadata(normalizeOriginalName(request.getOriginalName()), request.getContentType());
        Optional<FileRecord> existingRecord = fileRecordMapper.findNormalByMd5AndSize(currentOwnerId(), fileMd5, fileSize);
        if (existingRecord.isPresent()) {
            return DirectUploadInitResponse.builder()
                    .instant(true)
                    .fileId(existingRecord.get().getFileId())
                    .file(FileResponse.from(existingRecord.get()))
                    .build();
        }
        validateQuotaForNewFile(fileSize, true, true);

        FileUploadTask task = buildUploadTask(
                FileUploadType.DIRECT,
                normalizeOriginalName(request.getOriginalName()),
                request.getContentType(),
                fileSize,
                fileMd5,
                null,
                null);
        fileUploadTaskMapper.insert(task);
        return DirectUploadInitResponse.builder()
                .instant(false)
                .uploadId(task.getUploadId())
                .fileId(task.getFileId())
                .uploadUrl(fileObjectStorageService.createUploadUrl(task.getObjectKey()))
                .objectKey(task.getObjectKey())
                .expireMinutes(Math.max(1, properties.getUploadUrlExpireMinutes()))
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileRecord completeDirectUpload(String uploadId) {
        return withUploadOperationLock(uploadId, FileUploadType.DIRECT, new UploadOperation<FileRecord>() {
            @Override
            public FileRecord execute(FileUploadTask task) {
                return completeDirectUploadInternal(task);
            }

            @Override
            public FileRecord onSuccess(FileUploadTask task) {
                return findCompletedFileRecord(task);
            }

            @Override
            public FileRecord onAborted(FileUploadTask task) {
                throw new IllegalStateException("上传任务已取消，不能继续完成");
            }
        });
    }

    private FileRecord completeDirectUploadInternal(FileUploadTask task) {
        StoredObject object = fileObjectStorageService.statObject(task.getObjectKey());
        validateObjectSize(task, object);
        validateQuotaForNewFile(task.getFileSize(), false, false);
        try (java.io.InputStream inputStream = fileObjectStorageService.openObject(task.getObjectKey())) {
            fileSecurityScanner.scan(inputStream, task.getOriginalName(), object.getContentType(), object.getSize());
            FileRecord record = buildFileRecord(task, object);
            fileRecordMapper.insert(record);
            markTaskSuccess(task.getUploadId());
            log.info("direct upload completed, uploadId={}, fileId={}, size={}",
                    task.getUploadId(), record.getFileId(), record.getFileSize());
            return record;
        } catch (RuntimeException ex) {
            fileObjectStorageService.deleteQuietly(task.getObjectKey());
            throw ex;
        } catch (IOException ex) {
            fileObjectStorageService.deleteQuietly(task.getObjectKey());
            throw new IllegalStateException("读取直传文件内容失败", ex);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MultipartUploadInitResponse initMultipartUpload(MultipartUploadInitRequest request) {
        validateMultipartUploadInitRequest(request);
        String fileMd5 = normalizeFileMd5(request.getFileMd5());
        long fileSize = normalizePositiveFileSize(request.getFileSize());
        validateUploadMetadata(normalizeOriginalName(request.getOriginalName()), request.getContentType());
        Optional<FileRecord> existingRecord = fileRecordMapper.findNormalByMd5AndSize(currentOwnerId(), fileMd5, fileSize);
        if (existingRecord.isPresent()) {
            return MultipartUploadInitResponse.builder()
                    .instant(true)
                    .fileId(existingRecord.get().getFileId())
                    .file(FileResponse.from(existingRecord.get()))
                    .build();
        }
        validateQuotaForNewFile(fileSize, true, true);

        long partSize = normalizePartSize(request.getPartSize());
        int partCount = calculatePartCount(fileSize, partSize);
        FileUploadTask task = buildUploadTask(
                FileUploadType.MULTIPART,
                normalizeOriginalName(request.getOriginalName()),
                request.getContentType(),
                fileSize,
                fileMd5,
                partSize,
                partCount);
        fileUploadTaskMapper.insert(task);

        return buildMultipartUploadInitResponse(task);
    }

    @Override
    public MultipartUploadInitResponse resumeMultipartUpload(String uploadId) {
        FileUploadTask task = findUploadingTask(uploadId, FileUploadType.MULTIPART);
        return buildMultipartUploadInitResponse(task);
    }

    @Override
    public MultipartPartsResponse listMultipartParts(String uploadId) {
        FileUploadTask task = findTask(uploadId, FileUploadType.MULTIPART);
        Set<Integer> uploadedPartSet = new HashSet<Integer>();
        for (String objectKey : fileObjectStorageService.listObjectKeys(task.getPartObjectPrefix())) {
            int partNumber = parsePartNumber(task, objectKey);
            if (partNumber > 0) {
                uploadedPartSet.add(partNumber);
            }
        }
        List<Integer> uploadedParts = new ArrayList<Integer>(uploadedPartSet);
        Collections.sort(uploadedParts);
        return MultipartPartsResponse.builder()
                .uploadId(task.getUploadId())
                .partCount(task.getPartCount())
                .uploadedParts(uploadedParts)
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileRecord completeMultipartUpload(String uploadId) {
        return withUploadOperationLock(uploadId, FileUploadType.MULTIPART, new UploadOperation<FileRecord>() {
            @Override
            public FileRecord execute(FileUploadTask task) {
                return completeMultipartUploadInternal(task);
            }

            @Override
            public FileRecord onSuccess(FileUploadTask task) {
                return findCompletedFileRecord(task);
            }

            @Override
            public FileRecord onAborted(FileUploadTask task) {
                throw new IllegalStateException("上传任务已取消，不能继续完成");
            }
        });
    }

    private FileRecord completeMultipartUploadInternal(FileUploadTask task) {
        List<String> partObjectKeys = new ArrayList<String>(task.getPartCount());
        for (int partNumber = 1; partNumber <= task.getPartCount(); partNumber++) {
            String partObjectKey = buildPartObjectKey(task, partNumber);
            StoredObject partObject = statPartObjectWithRetry(partObjectKey, partNumber);
            long expectedSize = calculateExpectedPartSize(
                    task.getFileSize(), task.getPartSize(), partNumber, task.getPartCount());
            if (partObject.getSize() != expectedSize) {
                throw new IllegalStateException("分片大小不匹配，partNumber=" + partNumber
                        + ", expected=" + expectedSize + ", actual=" + partObject.getSize());
            }
            partObjectKeys.add(partObjectKey);
        }

        boolean composed = false;
        try {
            fileObjectStorageService.composeObject(task.getObjectKey(), partObjectKeys, task.getContentType());
            composed = true;
            StoredObject object = fileObjectStorageService.statObject(task.getObjectKey());
            validateObjectSize(task, object);
            validateQuotaForNewFile(task.getFileSize(), false, false);
            try (java.io.InputStream inputStream = fileObjectStorageService.openObject(task.getObjectKey())) {
                fileSecurityScanner.scan(inputStream, task.getOriginalName(), object.getContentType(), object.getSize());
                FileRecord record = buildFileRecord(task, object);
                fileRecordMapper.insert(record);
                markTaskSuccess(task.getUploadId());
                registerDeleteAfterCommit(partObjectKeys);
                log.info("multipart upload completed, uploadId={}, fileId={}, partCount={}, size={}",
                        task.getUploadId(), record.getFileId(), task.getPartCount(), record.getFileSize());
                return record;
            }
        } catch (RuntimeException ex) {
            if (composed) {
                fileObjectStorageService.deleteQuietly(task.getObjectKey());
            }
            fileObjectStorageService.deleteQuietly(partObjectKeys);
            throw ex;
        } catch (IOException ex) {
            if (composed) {
                fileObjectStorageService.deleteQuietly(task.getObjectKey());
            }
            fileObjectStorageService.deleteQuietly(partObjectKeys);
            throw new IllegalStateException("读取分片合并文件内容失败", ex);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void abortMultipartUpload(String uploadId) {
        withUploadOperationLock(uploadId, FileUploadType.MULTIPART, new UploadOperation<Void>() {
            @Override
            public Void execute(FileUploadTask task) {
                markTaskAborted(task.getUploadId());
                registerDeleteAfterCommit(fileObjectStorageService.listObjectKeys(task.getPartObjectPrefix()));
                log.info("multipart upload aborted, uploadId={}", task.getUploadId());
                return null;
            }

            @Override
            public Void onSuccess(FileUploadTask task) {
                log.info("multipart upload abort skipped because upload already completed, uploadId={}",
                        task.getUploadId());
                return null;
            }

            @Override
            public Void onAborted(FileUploadTask task) {
                log.info("multipart upload abort repeated, uploadId={}", task.getUploadId());
                return null;
            }
        });
    }

    @Override
    public Optional<FileRecord> findNormalFile(String fileId) {
        if (fileId == null || fileId.trim().isEmpty()) {
            return Optional.empty();
        }
        return fileRecordMapper.findNormalByFileId(currentOwnerId(), fileId.trim());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Optional<String> createDownloadUrl(String fileId) {
        Optional<FileRecord> recordOptional = findNormalFile(fileId);
        if (!recordOptional.isPresent()) {
            return Optional.empty();
        }
        FileRecord record = recordOptional.get();
        try {
            fileObjectStorageService.statObject(record.getObjectKey());
            return Optional.of(fileObjectStorageService.createDownloadUrl(record.getObjectKey(), record.getOriginalName()));
        } catch (RuntimeException ex) {
            fileRecordMapper.markDeleted(currentOwnerId(), record.getFileId());
            compensationService.recordPending(
                    "FILE",
                    record.getFileId(),
                    CompensationFailureType.OBJECT_MISSING.name(),
                    "objectKey=" + record.getObjectKey());
            log.warn("create file download url failed, fileId={}, objectKey={}", record.getFileId(), record.getObjectKey(), ex);
            return Optional.empty();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String fileId) {
        FileRecord record = findNormalFile(fileId)
                .orElseThrow(() -> new IllegalArgumentException("文件不存在"));
        int updated = fileRecordMapper.markDeleted(currentOwnerId(), record.getFileId());
        if (updated == 0) {
            throw new IllegalArgumentException("文件不存在");
        }
        registerDeleteAfterCommit(record.getObjectKey());
        log.info("file deleted, fileId={}, objectKey={}", record.getFileId(), record.getObjectKey());
    }

    @Override
    public FilePageResponse page(FilePageQueryRequest request) {
        FilePageQueryRequest safeRequest = request == null ? new FilePageQueryRequest() : request;
        int pageNo = normalizePageNo(safeRequest.getPageNo());
        int pageSize = normalizePageSize(safeRequest.getPageSize());
        String originalName = normalizeQueryText(safeRequest.getOriginalName());
        String fileExt = normalizeFileExt(safeRequest.getFileExt());
        int offset = (pageNo - 1) * pageSize;

        String ownerId = currentOwnerId();
        long total = fileRecordMapper.countNormal(ownerId, originalName, fileExt);
        List<FileRecord> records = fileRecordMapper.listNormalPage(ownerId, originalName, fileExt, offset, pageSize);
        List<FileResponse> responseRecords = new ArrayList<FileResponse>(records.size());
        for (FileRecord record : records) {
            responseRecords.add(FileResponse.from(record));
        }
        return FilePageResponse.builder()
                .total(total)
                .pageNo(pageNo)
                .pageSize(pageSize)
                .records(responseRecords)
                .build();
    }

    private FileRecord buildFileRecord(String fileId,
                                       String originalName,
                                       String fileExt,
                                       String contentType,
                                       StoredFile storedFile) {
        LocalDateTime now = LocalDateTime.now();
        return FileRecord.builder()
                .fileId(fileId)
                .ownerId(currentOwnerId())
                .originalName(originalName)
                .objectKey(storedFile.getObjectKey())
                .bucketName(storedFile.getBucketName())
                .contentType(contentType)
                .fileSize(storedFile.getFileSize())
                .fileMd5(storedFile.getFileMd5())
                .fileExt(fileExt)
                .storageType(StorageType.MINIO.name())
                .status(FileStatus.NORMAL.name())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private FileRecord buildFileRecord(FileUploadTask task, StoredObject object) {
        LocalDateTime now = LocalDateTime.now();
        return FileRecord.builder()
                .fileId(task.getFileId())
                .ownerId(task.getOwnerId())
                .originalName(task.getOriginalName())
                .objectKey(task.getObjectKey())
                .bucketName(task.getBucketName())
                .contentType(task.getContentType())
                .fileSize(object.getSize())
                .fileMd5(task.getFileMd5())
                .fileExt(task.getFileExt())
                .storageType(StorageType.MINIO.name())
                .status(FileStatus.NORMAL.name())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private FileUploadTask buildUploadTask(FileUploadType uploadType,
                                           String originalName,
                                           String contentType,
                                           long fileSize,
                                           String fileMd5,
                                           Long partSize,
                                           Integer partCount) {
        String uploadId = UUID.randomUUID().toString().replace("-", "");
        String fileId = UUID.randomUUID().toString().replace("-", "");
        String fileExt = resolveFileExt(originalName);
        String objectKey = buildObjectKey(fileId, fileExt);
        LocalDateTime now = LocalDateTime.now();
        String partObjectPrefix = uploadType == FileUploadType.MULTIPART
                ? buildPartObjectPrefix(uploadId)
                : null;
        return FileUploadTask.builder()
                .uploadId(uploadId)
                .fileId(fileId)
                .ownerId(currentOwnerId())
                .uploadType(uploadType.name())
                .originalName(originalName)
                .objectKey(objectKey)
                .partObjectPrefix(partObjectPrefix)
                .bucketName(fileObjectStorageService.bucketName())
                .contentType(contentType)
                .fileSize(fileSize)
                .fileMd5(fileMd5)
                .fileExt(fileExt)
                .status(FileUploadStatus.UPLOADING.name())
                .partSize(partSize)
                .partCount(partCount)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private FileUploadTask findTask(String uploadId, FileUploadType expectedUploadType) {
        if (uploadId == null || uploadId.trim().isEmpty()) {
            throw new IllegalArgumentException("上传任务不存在");
        }
        FileUploadTask task = fileUploadTaskMapper.findByUploadId(currentOwnerId(), uploadId.trim())
                .orElseThrow(() -> new IllegalArgumentException("上传任务不存在"));
        if (!expectedUploadType.name().equals(task.getUploadType())) {
            throw new IllegalArgumentException("上传任务类型不匹配");
        }
        return task;
    }

    private FileUploadTask findUploadingTask(String uploadId, FileUploadType expectedUploadType) {
        FileUploadTask task = findTask(uploadId, expectedUploadType);
        if (!FileUploadStatus.UPLOADING.name().equals(task.getStatus())) {
            throw new IllegalStateException("上传任务状态不允许继续操作，status=" + task.getStatus());
        }
        return task;
    }

    private <T> T withUploadOperationLock(String uploadId,
                                          FileUploadType expectedUploadType,
                                          UploadOperation<T> operation) {
        String normalizedUploadId = normalizeUploadId(uploadId);
        String ownerId = currentOwnerId();
        String lockKey = buildUploadOperationLockKey(ownerId, normalizedUploadId);
        String ownerToken = UUID.randomUUID().toString().replace("-", "");
        java.time.Duration ttl = java.time.Duration.ofSeconds(
                Math.max(60, properties.getUploadOperationLockTtlSeconds()));
        if (!distributedLockService.tryLock(lockKey, ownerToken, ttl)) {
            throw new IllegalStateException("上传任务正在处理中，请稍后重试");
        }
        try {
            FileUploadTask task = findTask(normalizedUploadId, expectedUploadType);
            if (FileUploadStatus.SUCCESS.name().equals(task.getStatus())) {
                return operation.onSuccess(task);
            }
            if (FileUploadStatus.ABORTED.name().equals(task.getStatus())) {
                return operation.onAborted(task);
            }
            if (!FileUploadStatus.UPLOADING.name().equals(task.getStatus())) {
                throw new IllegalStateException("上传任务状态不允许继续操作，status=" + task.getStatus());
            }
            return operation.execute(task);
        } finally {
            distributedLockService.release(lockKey, ownerToken);
        }
    }

    private FileRecord findCompletedFileRecord(FileUploadTask task) {
        return fileRecordMapper.findNormalByFileId(currentOwnerId(), task.getFileId())
                .orElseThrow(() -> new IllegalStateException("上传任务已完成但文件记录不存在"));
    }

    private String normalizeUploadId(String uploadId) {
        if (uploadId == null || uploadId.trim().isEmpty()) {
            throw new IllegalArgumentException("上传任务不存在");
        }
        return uploadId.trim();
    }

    private String buildUploadOperationLockKey(String ownerId, String uploadId) {
        String prefix = properties.getUploadOperationLockKeyPrefix();
        if (prefix == null || prefix.trim().isEmpty()) {
            prefix = "file:upload:operation:";
        }
        return prefix.trim() + ownerId + ":" + uploadId;
    }

    private interface UploadOperation<T> {
        T execute(FileUploadTask task);

        T onSuccess(FileUploadTask task);

        T onAborted(FileUploadTask task);
    }

    private MultipartUploadInitResponse buildMultipartUploadInitResponse(FileUploadTask task) {
        List<PartUploadUrlResponse> parts = new ArrayList<PartUploadUrlResponse>(task.getPartCount());
        for (int partNumber = 1; partNumber <= task.getPartCount(); partNumber++) {
            String partObjectKey = buildPartObjectKey(task, partNumber);
            parts.add(PartUploadUrlResponse.builder()
                    .partNumber(partNumber)
                    .objectKey(partObjectKey)
                    .uploadUrl(fileObjectStorageService.createUploadUrl(partObjectKey))
                    .expectedSize(calculateExpectedPartSize(task.getFileSize(), task.getPartSize(), partNumber, task.getPartCount()))
                    .build());
        }
        return MultipartUploadInitResponse.builder()
                .instant(false)
                .uploadId(task.getUploadId())
                .fileId(task.getFileId())
                .fileSize(task.getFileSize())
                .partSize(task.getPartSize())
                .partCount(task.getPartCount())
                .expireMinutes(Math.max(1, properties.getUploadUrlExpireMinutes()))
                .parts(parts)
                .build();
    }

    private StoredObject statPartObjectWithRetry(String partObjectKey, int partNumber) {
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= PART_STAT_MAX_ATTEMPTS; attempt++) {
            try {
                return fileObjectStorageService.statObject(partObjectKey);
            } catch (RuntimeException ex) {
                lastException = ex;
                if (attempt >= PART_STAT_MAX_ATTEMPTS) {
                    break;
                }
                log.warn("stat multipart object failed, will retry, partNumber={}, attempt={}, objectKey={}",
                        partNumber, attempt, partObjectKey, ex);
                sleepBeforePartStatRetry();
            }
        }
        throw lastException;
    }

    private void sleepBeforePartStatRetry() {
        try {
            Thread.sleep(PART_STAT_RETRY_INTERVAL_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待重试读取分片对象时被中断", ex);
        }
    }

    private void markTaskSuccess(String uploadId) {
        int updated = fileUploadTaskMapper.markSuccess(currentOwnerId(), uploadId);
        if (updated == 0) {
            throw new IllegalStateException("上传任务状态更新失败");
        }
    }

    private void markTaskAborted(String uploadId) {
        int updated = fileUploadTaskMapper.markAborted(currentOwnerId(), uploadId);
        if (updated == 0) {
            throw new IllegalStateException("上传任务状态更新失败");
        }
    }

    private void validateUploadFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        if (file.getSize() <= 0L) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
    }

    private void validateUploadMetadata(String originalName, String contentType) {
        fileSecurityScanner.validateMetadata(originalName, contentType);
    }

    private void validateDirectUploadInitRequest(DirectUploadInitRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("上传参数不能为空");
        }
        normalizeOriginalName(request.getOriginalName());
        normalizePositiveFileSize(request.getFileSize());
        normalizeFileMd5(request.getFileMd5());
    }

    private void validateMultipartUploadInitRequest(MultipartUploadInitRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("上传参数不能为空");
        }
        normalizeOriginalName(request.getOriginalName());
        long fileSize = normalizePositiveFileSize(request.getFileSize());
        normalizeFileMd5(request.getFileMd5());
        long partSize = normalizePartSize(request.getPartSize());
        calculatePartCount(fileSize, partSize);
    }

    private void validateQuotaForNewFile(long fileSize,
                                         boolean includeUploadingTasks,
                                         boolean checkActiveUploadTasks) {
        validateSingleFileSize(fileSize);
        validateDailyUploadCount();
        if (checkActiveUploadTasks) {
            validateActiveUploadTasks();
        }
        validateTotalStorageQuota(fileSize, includeUploadingTasks);
    }

    private void validateSingleFileSize(long fileSize) {
        long maxFileSizeBytes = properties.getMaxFileSizeBytes();
        if (maxFileSizeBytes > 0L && fileSize > maxFileSizeBytes) {
            throw new IllegalStateException("文件大小超过单文件限制，maxBytes="
                    + maxFileSizeBytes + ", actualBytes=" + fileSize);
        }
    }

    private void validateDailyUploadCount() {
        int maxDailyUploadCount = properties.getMaxDailyUploadCountPerOwner();
        if (maxDailyUploadCount <= 0) {
            return;
        }
        long todayCount = fileRecordMapper.countNormalCreatedAtOrAfter(currentOwnerId(), LocalDate.now().atStartOfDay());
        if (todayCount >= maxDailyUploadCount) {
            throw new IllegalStateException("今日上传次数已达上限，maxDailyCount=" + maxDailyUploadCount);
        }
    }

    private void validateActiveUploadTasks() {
        int maxActiveUploadTasks = properties.getMaxActiveUploadTasksPerOwner();
        if (maxActiveUploadTasks <= 0) {
            return;
        }
        long activeUploadTasks = fileUploadTaskMapper.countUploadingByOwner(currentOwnerId());
        if (activeUploadTasks >= maxActiveUploadTasks) {
            throw new IllegalStateException("活跃上传任务数已达上限，maxActiveTasks=" + maxActiveUploadTasks);
        }
    }

    private void validateTotalStorageQuota(long fileSize, boolean includeUploadingTasks) {
        long maxTotalStorageBytes = properties.getMaxTotalStorageBytesPerOwner();
        if (maxTotalStorageBytes <= 0L) {
            return;
        }
        long currentBytes = fileRecordMapper.sumNormalFileSize(currentOwnerId());
        if (includeUploadingTasks) {
            currentBytes = safeAdd(currentBytes, fileUploadTaskMapper.sumUploadingFileSize(currentOwnerId()));
        }
        long expectedBytes = safeAdd(currentBytes, fileSize);
        if (expectedBytes > maxTotalStorageBytes) {
            throw new IllegalStateException("用户文件存储空间不足，maxBytes="
                    + maxTotalStorageBytes + ", currentBytes=" + currentBytes + ", requestBytes=" + fileSize);
        }
    }

    private long safeAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private void validateObjectSize(FileUploadTask task, StoredObject object) {
        if (object.getSize() != task.getFileSize()) {
            throw new IllegalStateException("文件大小不匹配，expected="
                    + task.getFileSize() + ", actual=" + object.getSize());
        }
    }

    private String normalizeFileMd5(String fileMd5) {
        if (fileMd5 == null || fileMd5.trim().isEmpty()) {
            throw new IllegalArgumentException("文件 MD5 不能为空");
        }
        String normalized = fileMd5.trim().toLowerCase();
        if (!normalized.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("文件 MD5 格式不正确");
        }
        return normalized;
    }

    private long normalizePositiveFileSize(Long fileSize) {
        if (fileSize == null || fileSize <= 0L) {
            throw new IllegalArgumentException("文件大小必须大于 0");
        }
        return fileSize;
    }

    private long normalizePartSize(Long partSize) {
        long configuredPartSize = Math.max(MIN_MULTIPART_PART_SIZE, properties.getMultipartPartSize());
        if (partSize == null || partSize <= 0L) {
            return configuredPartSize;
        }
        return Math.max(MIN_MULTIPART_PART_SIZE, partSize);
    }

    private int calculatePartCount(long fileSize, long partSize) {
        long partCount = (fileSize + partSize - 1L) / partSize;
        int maxPartCount = Math.max(1, properties.getMultipartMaxPartCount());
        if (partCount > maxPartCount) {
            throw new IllegalArgumentException("文件分片数量超过上限，partCount=" + partCount
                    + ", maxPartCount=" + maxPartCount);
        }
        return (int) partCount;
    }

    private long calculateExpectedPartSize(long fileSize, long partSize, int partNumber, int partCount) {
        if (partNumber == partCount) {
            return fileSize - partSize * (partCount - 1L);
        }
        return partSize;
    }

    private String buildObjectKey(String fileId, String fileExt) {
        LocalDate today = LocalDate.now();
        StringBuilder builder = new StringBuilder();
        builder.append(normalizeObjectPrefix(properties.getObjectPrefix()))
                .append('/')
                .append(today.getYear())
                .append('/')
                .append(String.format("%02d", today.getMonthValue()))
                .append('/')
                .append(String.format("%02d", today.getDayOfMonth()))
                .append('/')
                .append(fileId);
        if (fileExt != null && !fileExt.isEmpty()) {
            builder.append('.').append(fileExt);
        }
        return builder.toString();
    }

    private String buildPartObjectPrefix(String uploadId) {
        return normalizeObjectPrefix(properties.getMultipartObjectPrefix()) + "/" + uploadId;
    }

    private String buildPartObjectKey(FileUploadTask task, int partNumber) {
        return task.getPartObjectPrefix() + "/" + String.format("%05d", partNumber) + ".part";
    }

    private int parsePartNumber(FileUploadTask task, String objectKey) {
        if (objectKey == null || !objectKey.startsWith(task.getPartObjectPrefix() + "/")) {
            return -1;
        }
        String fileName = objectKey.substring((task.getPartObjectPrefix() + "/").length());
        if (!fileName.endsWith(".part")) {
            return -1;
        }
        String numberText = fileName.substring(0, fileName.length() - ".part".length());
        try {
            return Integer.parseInt(numberText);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private String normalizeObjectPrefix(String objectPrefix) {
        if (objectPrefix == null || objectPrefix.trim().isEmpty()) {
            return "files/general";
        }
        return objectPrefix.trim().replaceAll("^/+", "").replaceAll("/+$", "");
    }

    private String normalizeOriginalName(String originalName) {
        if (originalName == null || originalName.trim().isEmpty()) {
            return "unknown";
        }
        String normalized = originalName.trim().replace('\\', '/');
        int slashIndex = normalized.lastIndexOf('/');
        if (slashIndex >= 0) {
            normalized = normalized.substring(slashIndex + 1);
        }
        normalized = normalized.replace("\u0000", "");
        if (normalized.isEmpty()) {
            return "unknown";
        }
        return normalized.length() > 255 ? normalized.substring(normalized.length() - 255) : normalized;
    }

    private String resolveFileExt(String originalName) {
        int dotIndex = originalName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == originalName.length() - 1) {
            return "";
        }
        return normalizeFileExt(originalName.substring(dotIndex + 1));
    }

    private String normalizeFileExt(String fileExt) {
        if (fileExt == null) {
            return null;
        }
        String normalized = fileExt.trim().toLowerCase();
        if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        return normalized.length() > 32 ? normalized.substring(0, 32) : normalized;
    }

    private String normalizeQueryText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        return text.trim();
    }

    private String currentOwnerId() {
        return UserContextHolder.get()
                .map(CurrentUser::getUserId)
                .filter(this::hasText)
                .map(this::normalizeOwnerId)
                .orElse("anonymous");
    }

    private String normalizeOwnerId(String ownerId) {
        String normalized = ownerId == null ? "anonymous" : ownerId.trim();
        return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private int normalizePageNo(Integer pageNo) {
        if (pageNo == null || pageNo < 1) {
            return 1;
        }
        return pageNo;
    }

    private int normalizePageSize(Integer pageSize) {
        int configuredMaxPageSize = Math.max(1, properties.getMaxPageSize());
        if (pageSize == null || pageSize < 1) {
            return Math.min(DEFAULT_PAGE_SIZE, configuredMaxPageSize);
        }
        return Math.min(pageSize, configuredMaxPageSize);
    }

    private void registerDeleteAfterCommit(String objectKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            fileObjectStorageService.deleteQuietly(objectKey);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                fileObjectStorageService.deleteQuietly(objectKey);
            }
        });
    }

    private void registerDeleteAfterCommit(List<String> objectKeys) {
        if (objectKeys == null || objectKeys.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            fileObjectStorageService.deleteQuietly(objectKeys);
            return;
        }
        List<String> pendingDeleteObjectKeys = new ArrayList<String>(objectKeys);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                fileObjectStorageService.deleteQuietly(pendingDeleteObjectKeys);
            }
        });
    }
}
