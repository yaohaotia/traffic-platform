package com.yao.traffic.shared.web;

import org.springframework.http.HttpStatus;

/** Expected business rejection. Only deliberately public messages belong here. */
public class BusinessException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public BusinessException(HttpStatus status, String code, String message) {
        super(message);
        if (!status.is4xxClientError()) throw new IllegalArgumentException("Business status must be 4xx");
        this.status = status;
        this.code = code;
    }

    public String code() { return code; }
    public HttpStatus status() { return status; }
}
