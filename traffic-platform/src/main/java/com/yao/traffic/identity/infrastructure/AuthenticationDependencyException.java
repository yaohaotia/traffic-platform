package com.yao.traffic.identity.infrastructure;

import org.springframework.security.core.AuthenticationException;

/** Kept distinct from AuthenticationServiceException so the bearer filter routes it to our 503 entry point. */
public class AuthenticationDependencyException extends AuthenticationException {
    public AuthenticationDependencyException(Throwable cause) {
        super("Required authentication dependency unavailable", cause);
    }
}
