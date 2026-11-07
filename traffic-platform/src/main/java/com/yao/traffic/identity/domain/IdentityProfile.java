package com.yao.traffic.identity.domain;

import java.util.List;

public record IdentityProfile(long userId, String username, List<String> roles,
                              List<String> permissions, List<String> regionCodes) { }
