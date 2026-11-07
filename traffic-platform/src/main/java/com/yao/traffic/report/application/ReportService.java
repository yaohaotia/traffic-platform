package com.yao.traffic.report.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yao.traffic.campaign.application.CampaignService;
import com.yao.traffic.evidence.application.EvidenceService;
import com.yao.traffic.evidence.infrastructure.EvidenceRepository;
import com.yao.traffic.report.domain.ReportView;
import com.yao.traffic.report.domain.SubmissionPolicy;
import com.yao.traffic.report.infrastructure.ReportMapper;
import com.yao.traffic.report.infrastructure.ReportRateLimiter;
import com.yao.traffic.report.infrastructure.ReportRecord;
import com.yao.traffic.shared.security.CurrentActor;
import com.yao.traffic.shared.web.BusinessException;
import com.yao.traffic.shared.web.PageResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ReportService {
    private final JdbcTemplate jdbc;
    private final ReportMapper reports;
    private final CampaignService campaigns;
    private final EvidenceRepository evidence;
    private final SubmissionPolicy policy;
    private final ReportRateLimiter limiter;

    public ReportService(JdbcTemplate jdbc, ReportMapper reports, CampaignService campaigns,
                         EvidenceRepository evidence, SubmissionPolicy policy, ReportRateLimiter limiter) {
        this.jdbc = jdbc; this.reports = reports; this.campaigns = campaigns;
        this.evidence = evidence; this.policy = policy; this.limiter = limiter;
    }

    @PreAuthorize("hasAuthority('report:submit')")
    @Transactional
    public ReportView submit(long campaignId, long evidenceId, String location, String description,
                             Instant captureTime, String requestKey) {
        if (requestKey == null || !requestKey.matches("[A-Za-z0-9._:-]{1,128}"))
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "请提供合法的Idempotency-Key");
        // MySQL's default text collation is often case-insensitive; opaque client keys are not.
        // Store a lowercase hex digest so distinct keys never collapse under that collation.
        String storedRequestKey = EvidenceService.sha256(requestKey.getBytes(StandardCharsets.UTF_8));
        var actor = CurrentActor.profile();
        // One short transaction per submitting user; lock before any snapshot read.
        // This also serializes a concurrent user disable with submission.
        List<Boolean> enabled = jdbc.query("SELECT enabled FROM identity_user WHERE id = ? FOR UPDATE",
                (rs, row) -> rs.getBoolean(1), actor.userId());
        if (enabled.isEmpty() || !enabled.getFirst())
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_SESSION", "用户已停用");
        String fingerprint = fingerprint(campaignId, evidenceId, location, description, captureTime);
        ReportRecord existing = reports.selectOne(new QueryWrapper<ReportRecord>()
                .eq("user_id", actor.userId()).eq("request_key", storedRequestKey));
        if (existing != null) {
            if (!existing.getRequestHash().equals(fingerprint))
                throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "同一请求键不能用于不同内容");
            return view(existing);
        }
        var campaign = campaigns.required(campaignId, true);
        var image = evidence.required(evidenceId);
        Instant now = Instant.now();
        policy.validate(actor, campaign, image, captureTime, now);
        Long duplicates = reports.selectCount(new QueryWrapper<ReportRecord>().eq("campaign_id", campaignId)
                .eq("user_id", actor.userId()).eq("evidence_sha256", image.sha256()));
        if (duplicates != null && duplicates > 0)
            throw new BusinessException(HttpStatus.CONFLICT, "DUPLICATE_EVIDENCE", "此图片已向该任务提交过");
        limiter.acquire(actor.userId());
        ReportRecord report = new ReportRecord();
        report.setCampaignId(campaignId); report.setUserId(actor.userId()); report.setEvidenceId(evidenceId);
        report.setEvidenceSha256(image.sha256()); report.setRegionCode(campaign.regionCode());
        report.setLocation(location); report.setDescription(description); report.setCaptureTime(captureTime);
        report.setRewardAmount(campaign.rewardAmount()); report.setDetectionStatus("PENDING");
        report.setReviewStatus("WAITING_DETECTION"); report.setRewardStatus("NONE");
        report.setRequestKey(storedRequestKey); report.setRequestHash(fingerprint); report.setCreatedAt(now);
        try {
            if (reports.insert(report) != 1) throw new IllegalStateException("Report was not saved");
        } catch (DuplicateKeyException conflict) {
            throw new BusinessException(HttpStatus.CONFLICT, "DUPLICATE_EVIDENCE", "此图片或请求已经提交过");
        }
        String taskId = UUID.randomUUID().toString(), eventId = UUID.randomUUID().toString();
        if (jdbc.update("INSERT INTO detection_task (id, report_id, status, attempt_count, version) VALUES (?, ?, 'PENDING', 0, 0)",
                taskId, report.getId()) != 1) throw new IllegalStateException("Detection task was not saved");
        // All values below are internally generated UUIDs/numeric IDs, not user-controlled text.
        String payload = "{\"schemaVersion\":1,\"eventId\":\"" + eventId + "\",\"eventType\":\"REPORT_SUBMITTED\",\"taskId\":\""
                + taskId + "\",\"reportId\":" + report.getId() + ",\"evidenceId\":" + evidenceId + ",\"userId\":" + actor.userId() + "}";
        if (jdbc.update("""
                INSERT INTO outbox_event (event_id, aggregate_type, aggregate_id, event_type, payload, status, attempt_count)
                VALUES (?, 'REPORT', ?, 'REPORT_SUBMITTED', ?, 'PENDING', 0)
                """, eventId, report.getId(), payload) != 1) throw new IllegalStateException("Outbox event was not saved");
        // Return the stored time precision on both first submission and subsequent replay.
        return view(reports.selectById(report.getId()));
    }

    @PreAuthorize("hasAuthority('report:read') or hasAuthority('report:review')")
    public ReportView get(long id) {
        ReportRecord report = reports.selectById(id);
        if (report == null) throw new BusinessException(HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND", "线索不存在");
        var actor = CurrentActor.profile();
        if (report.getUserId() != actor.userId() && !(actor.permissions().contains("report:review")
                && actor.regionCodes().contains(report.getRegionCode())))
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "无权查看此线索");
        return view(report);
    }

    @PreAuthorize("hasAuthority('report:read') or hasAuthority('report:review')")
    public PageResult<ReportView> list(int page, int size) {
        int offset = PageResult.offset(page, size);
        var actor = CurrentActor.profile();
        QueryWrapper<ReportRecord> query = new QueryWrapper<>();
        if (actor.permissions().contains("report:review") && !actor.regionCodes().isEmpty())
            query.and(scope -> scope.eq("user_id", actor.userId()).or().in("region_code", actor.regionCodes()));
        else query.eq("user_id", actor.userId());
        query.orderByDesc("id").last("LIMIT " + size + " OFFSET " + offset);
        return new PageResult<>(reports.selectList(query).stream().map(ReportService::view).toList(), page, size);
    }

    private static ReportView view(ReportRecord report) {
        return new ReportView(report.getId(), report.getCampaignId(), report.getUserId(), report.getEvidenceId(),
                report.getRegionCode(), report.getLocation(), report.getDescription(), report.getCaptureTime(),
                report.getRewardAmount(), report.getDetectionStatus(), report.getReviewStatus(), report.getRewardStatus());
    }

    private static String fingerprint(long campaignId, long evidenceId, String location, String description, Instant captureTime) {
        StringBuilder canonical = new StringBuilder();
        for (String value : List.of(Long.toString(campaignId), Long.toString(evidenceId), location, description, captureTime.toString()))
            canonical.append(value.length()).append(':').append(value);
        return EvidenceService.sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }
}
