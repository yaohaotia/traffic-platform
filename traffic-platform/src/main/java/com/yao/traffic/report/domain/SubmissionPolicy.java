package com.yao.traffic.report.domain;

import com.yao.traffic.campaign.domain.Campaign;
import com.yao.traffic.evidence.domain.Evidence;
import com.yao.traffic.identity.domain.IdentityProfile;
import com.yao.traffic.shared.web.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/** Ordered domain rules independent of HTTP, persistence and message delivery. */
@Component
public class SubmissionPolicy {
    private final List<Rule> rules = List.of(
            context -> {
                Campaign campaign = context.campaign();
                if (!"PUBLISHED".equals(campaign.state()) || context.now().isBefore(campaign.startsAt())
                        || !context.now().isBefore(campaign.endsAt()))
                    throw new BusinessException(HttpStatus.CONFLICT, "CAMPAIGN_UNAVAILABLE", "征集任务未开放或已结束");
            },
            context -> {
                if (!context.actor().regionCodes().contains(context.campaign().regionCode()))
                    throw new BusinessException(HttpStatus.FORBIDDEN, "REGION_FORBIDDEN", "无权向此区域提交线索");
            },
            context -> {
                if (context.evidence().ownerId() != context.actor().userId())
                    throw new BusinessException(HttpStatus.FORBIDDEN, "EVIDENCE_FORBIDDEN", "只能使用自己的图片证据");
            },
            context -> {
                if (context.captureTime().isAfter(context.now().plusSeconds(60)))
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_CAPTURE_TIME", "拍摄时间不能晚于当前时间");
            }
    );

    public void validate(IdentityProfile actor, Campaign campaign, Evidence evidence, Instant captureTime, Instant now) {
        Context context = new Context(actor, campaign, evidence, captureTime, now);
        rules.forEach(rule -> rule.check(context));
    }

    private interface Rule { void check(Context context); }
    private record Context(IdentityProfile actor, Campaign campaign, Evidence evidence, Instant captureTime, Instant now) { }
}
