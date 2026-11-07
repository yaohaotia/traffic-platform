package com.yao.traffic.report.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** Persistence bean; API views omit request hashes and internal deduplication fields. */
@TableName("traffic_report")
public class ReportRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long campaignId;
    private Long userId;
    private Long evidenceId;
    private String evidenceSha256;
    private String regionCode;
    private String location;
    private String description;
    private java.time.Instant captureTime;
    private java.math.BigDecimal rewardAmount;
    private String detectionStatus;
    private String reviewStatus;
    private String rewardStatus;
    private String requestKey;
    private String requestHash;
    private java.time.Instant createdAt;
    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    public Long getCampaignId() { return campaignId; }
    public void setCampaignId(Long value) { this.campaignId = value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { this.userId = value; }
    public Long getEvidenceId() { return evidenceId; }
    public void setEvidenceId(Long value) { this.evidenceId = value; }
    public String getEvidenceSha256() { return evidenceSha256; }
    public void setEvidenceSha256(String value) { this.evidenceSha256 = value; }
    public String getRegionCode() { return regionCode; }
    public void setRegionCode(String value) { this.regionCode = value; }
    public String getLocation() { return location; }
    public void setLocation(String value) { this.location = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { this.description = value; }
    public java.time.Instant getCaptureTime() { return captureTime; }
    public void setCaptureTime(java.time.Instant value) { this.captureTime = value; }
    public java.math.BigDecimal getRewardAmount() { return rewardAmount; }
    public void setRewardAmount(java.math.BigDecimal value) { this.rewardAmount = value; }
    public String getDetectionStatus() { return detectionStatus; }
    public void setDetectionStatus(String value) { this.detectionStatus = value; }
    public String getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(String value) { this.reviewStatus = value; }
    public String getRewardStatus() { return rewardStatus; }
    public void setRewardStatus(String value) { this.rewardStatus = value; }
    public String getRequestKey() { return requestKey; }
    public void setRequestKey(String value) { this.requestKey = value; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String value) { this.requestHash = value; }
    public java.time.Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.Instant value) { this.createdAt = value; }
}

