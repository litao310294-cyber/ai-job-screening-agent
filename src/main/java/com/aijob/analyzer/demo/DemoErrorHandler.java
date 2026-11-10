package com.aijob.analyzer.demo;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import static com.aijob.analyzer.demo.DemoModels.*;

@RestControllerAdvice(assignableTypes = {DemoController.class, com.aijob.analyzer.demo.profile.ProfileController.class, com.aijob.analyzer.rules.RuleController.class, com.aijob.analyzer.chat.ChatController.class})
public class DemoErrorHandler {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DemoErrorHandler.class);
    @ExceptionHandler(DemoException.class)
    public ResponseEntity<ApiError> business(DemoException e) {
        return ResponseEntity.status(e.status()).body(new ApiError(e.code(), e.getMessage()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, jakarta.validation.ConstraintViolationException.class})
    public ResponseEntity<ApiError> invalid(Exception e) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_INPUT", "请求字段或格式不合法"));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> internal(Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        LOG.error("HTTP failure type={} rootType={} sqlState={} sqlCode={}", e.getClass().getSimpleName(), root.getClass().getSimpleName(),
                root instanceof java.sql.SQLException sql ? sql.getSQLState() : "",
                root instanceof java.sql.SQLException sql ? sql.getErrorCode() : 0);
        return ResponseEntity.status(503).body(new ApiError("SERVICE_UNAVAILABLE", "本机服务暂时不可用，请使用相同请求标识查询或重试"));
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> tooLarge(Exception e) {
        return ResponseEntity.status(413).body(new ApiError("PDF_SIZE_INVALID", "PDF 文件不能超过 10 MiB"));
    }
}
