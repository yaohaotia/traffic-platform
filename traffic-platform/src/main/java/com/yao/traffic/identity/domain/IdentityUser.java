package com.yao.traffic.identity.domain;

public record IdentityUser(long id, String username, String passwordHash, boolean enabled, long authVersion) { }
