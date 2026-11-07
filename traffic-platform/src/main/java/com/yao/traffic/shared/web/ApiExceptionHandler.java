package com.yao.traffic.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> business(BusinessException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status()).body(error(exception.status().value(),
                exception.code(), exception.getMessage(), request, List.of()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> forbidden(AccessDeniedException exception, HttpServletRequest request) {
        return ResponseEntity.status(403).body(error(403, "ACCESS_DENIED", "无权执行此操作", request, List.of()));
    }

    @ExceptionHandler(DependencyUnavailableException.class)
    public ResponseEntity<ApiError> dependencyUnavailable(DependencyUnavailableException exception,
                                                         HttpServletRequest request) {
        LOG.error("Required dependency unavailable path={}", request.getRequestURI(), exception);
        return ResponseEntity.status(503).body(error(503, "DEPENDENCY_UNAVAILABLE", "必要服务暂时不可用，请稍后重试",
                request, List.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        LOG.error("Unhandled request failure path={}", request.getRequestURI(), exception);
        return ResponseEntity.internalServerError().body(error(500, "INTERNAL_ERROR",
                "服务器暂时无法处理请求", request, List.of()));
    }

    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public ResponseEntity<ApiError> duplicate(org.springframework.dao.DuplicateKeyException exception,HttpServletRequest request){
        return ResponseEntity.status(409).body(error(409,"RECORD_CONFLICT","记录已存在或请求与已有记录冲突",request,List.of()));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest webRequest) {
        List<ApiError.FieldViolation> violations = exception.getBindingResult().getFieldErrors().stream()
                .map(field -> new ApiError.FieldViolation(field.getField(), field.getDefaultMessage()))
                .toList();
        return new ResponseEntity<>(error(400, "VALIDATION_FAILED", "请求参数校验失败",
                request(webRequest), violations), headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest webRequest) {
        String code = switch (status.value()) {
            case 400 -> "INVALID_REQUEST";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> status.is5xxServerError() ? "INTERNAL_ERROR" : "REQUEST_REJECTED";
        };
        String message = switch (status.value()) {
            case 400 -> "请求参数或格式不正确";
            case 404 -> "请求的资源不存在";
            case 405 -> "请求方法不支持";
            case 415 -> "请求媒体类型不支持";
            default -> status.is5xxServerError() ? "服务器暂时无法处理请求" : "请求未被接受";
        };
        if (status.is5xxServerError()) LOG.error("MVC request failure", exception);
        return new ResponseEntity<>(error(status.value(), code, message, request(webRequest), List.of()),
                headers, status);
    }

    private static HttpServletRequest request(WebRequest webRequest) {
        return ((ServletWebRequest) webRequest).getRequest();
    }

    public static ApiError error(int status, String code, String message,
                                 HttpServletRequest request, List<ApiError.FieldViolation> violations) {
        Object requestId = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return new ApiError(Instant.now(), status, code, message, request.getRequestURI(),
                requestId == null ? "" : requestId.toString(), violations);
    }
}
