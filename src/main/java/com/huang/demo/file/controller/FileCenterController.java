package com.huang.demo.file.controller;

import com.huang.demo.file.api.dto.DirectUploadInitRequest;
import com.huang.demo.file.api.dto.DirectUploadInitResponse;
import com.huang.demo.file.api.dto.FilePageQueryRequest;
import com.huang.demo.file.api.dto.FilePageResponse;
import com.huang.demo.file.api.dto.FileResponse;
import com.huang.demo.file.api.dto.FileUploadResponse;
import com.huang.demo.file.api.dto.InstantUploadCheckRequest;
import com.huang.demo.file.api.dto.InstantUploadCheckResponse;
import com.huang.demo.file.api.dto.MultipartPartsResponse;
import com.huang.demo.file.api.dto.MultipartUploadInitRequest;
import com.huang.demo.file.api.dto.MultipartUploadInitResponse;
import com.huang.demo.file.domain.entity.FileRecord;
import com.huang.demo.file.service.FileCenterService;
import io.swagger.annotations.ApiOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/files")
public class FileCenterController {

    private static final Logger log = LoggerFactory.getLogger(FileCenterController.class);

    private final FileCenterService fileCenterService;

    public FileCenterController(FileCenterService fileCenterService) {
        this.fileCenterService = fileCenterService;
    }

    @ApiOperation("上传通用文件")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FileUploadResponse upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上传文件不能为空");
        }
        long start = System.currentTimeMillis();
        FileRecord record = fileCenterService.upload(file);
        long elapsed = System.currentTimeMillis() - start;
        log.info("file upload api finished, fileId={}, originalName={}, elapsedMs={}",
                record.getFileId(), record.getOriginalName(), elapsed);
        return FileUploadResponse.from(record, elapsed);
    }

    @ApiOperation("秒传检查")
    @PostMapping("/instant-check")
    public InstantUploadCheckResponse instantCheck(@RequestBody InstantUploadCheckRequest request) {
        try {
            return fileCenterService.instantCheck(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @ApiOperation("初始化客户端直传")
    @PostMapping("/direct/init")
    public DirectUploadInitResponse initDirectUpload(@RequestBody DirectUploadInitRequest request) {
        try {
            return fileCenterService.initDirectUpload(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @ApiOperation("确认客户端直传完成")
    @PostMapping("/direct/{uploadId}/complete")
    public FileResponse completeDirectUpload(@PathVariable("uploadId") String uploadId) {
        try {
            return FileResponse.from(fileCenterService.completeDirectUpload(uploadId));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    @ApiOperation("初始化客户端分片上传")
    @PostMapping("/multipart/init")
    public MultipartUploadInitResponse initMultipartUpload(@RequestBody MultipartUploadInitRequest request) {
        try {
            return fileCenterService.initMultipartUpload(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @ApiOperation("查询客户端分片上传进度")
    @GetMapping("/multipart/{uploadId}/parts")
    public MultipartPartsResponse listMultipartParts(@PathVariable("uploadId") String uploadId) {
        try {
            return fileCenterService.listMultipartParts(uploadId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @ApiOperation("完成客户端分片上传")
    @PostMapping("/multipart/{uploadId}/complete")
    public FileResponse completeMultipartUpload(@PathVariable("uploadId") String uploadId) {
        try {
            return FileResponse.from(fileCenterService.completeMultipartUpload(uploadId));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    @ApiOperation("取消客户端分片上传")
    @PostMapping("/multipart/{uploadId}/abort")
    public Map<String, Object> abortMultipartUpload(@PathVariable("uploadId") String uploadId) {
        try {
            fileCenterService.abortMultipartUpload(uploadId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("aborted", true);
        result.put("uploadId", uploadId);
        return result;
    }

    @ApiOperation("查询文件详情")
    @GetMapping("/{fileId}")
    public FileResponse detail(@PathVariable("fileId") String fileId) {
        FileRecord record = fileCenterService.findNormalFile(fileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在"));
        return FileResponse.from(record);
    }

    @ApiOperation("下载通用文件")
    @GetMapping("/{fileId}/download")
    public ResponseEntity<Void> download(@PathVariable("fileId") String fileId) {
        String downloadUrl = fileCenterService.createDownloadUrl(fileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在"));
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(downloadUrl))
                .build();
    }

    @ApiOperation("逻辑删除通用文件")
    @PostMapping("/{fileId}/delete")
    public Map<String, Object> delete(@PathVariable("fileId") String fileId) {
        fileCenterService.findNormalFile(fileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在"));
        fileCenterService.delete(fileId);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("deleted", true);
        result.put("fileId", fileId);
        return result;
    }

    @ApiOperation("分页查询通用文件")
    @PostMapping("/page")
    public FilePageResponse page(@RequestBody(required = false) FilePageQueryRequest request) {
        return fileCenterService.page(request);
    }
}
