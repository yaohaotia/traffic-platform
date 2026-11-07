package com.yao.traffic.shared.web;

import java.time.Instant;
import java.util.List;

/** Error envelope shared by MVC and, in the next phase, security entry points. */
public record ApiError(Instant timestamp, int status, String code, String message,
                       String path, String requestId, List<FieldViolation> violations) {
    public record FieldViolation(String field, String message) { }
}
