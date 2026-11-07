package com.yao.traffic.campaign.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record Campaign(long id, String title, String regionCode, Instant startsAt, Instant endsAt,
                       BigDecimal rewardAmount, BigDecimal budgetAmount, String state) { }
