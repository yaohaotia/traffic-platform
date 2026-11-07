package com.yao.traffic.shared.web;

/** A required infrastructure dependency is unavailable; never fall back to an unauthenticated mode. */
public class DependencyUnavailableException extends RuntimeException {
    public DependencyUnavailableException(String dependency, Throwable cause) {
        super(dependency + " is unavailable", cause);
    }
}
