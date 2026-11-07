package com.yao.traffic.report.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record ReportView(long id, long campaignId, long userId, long evidenceId, String regionCode,
                         String location, String description, Instant captureTime, BigDecimal rewardAmount,
                         String detectionStatus, String reviewStatus, String rewardStatus) { }
