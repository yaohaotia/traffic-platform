package com.yao.traffic.detection;

import java.util.UUID;

public final class DetectionModels {
    private DetectionModels() { }
    public record Event(int schemaVersion, String eventId, String eventType, String taskId,
                        long reportId, long evidenceId, long userId) {
        public boolean valid() {
            try {
                return schemaVersion == 1 && ("REPORT_SUBMITTED".equals(eventType) || "DETECTION_REQUESTED".equals(eventType))
                        && eventId != null && UUID.fromString(eventId).toString().equals(eventId)
                        && taskId != null && UUID.fromString(taskId).toString().equals(taskId)
                        && reportId > 0 && evidenceId > 0 && userId > 0;
            } catch (IllegalArgumentException exception) { return false; }
        }
    }
    public record Claim(String taskId, long reportId, long evidenceId, String objectKey,
                        long version, String leaseToken) { }
    public record Result(double score, double threshold, String modelVersion, boolean isAiGenerated) {
        public boolean valid() {
            return Double.isFinite(score) && Double.isFinite(threshold) && score >= 0 && score <= 1
                    && threshold >= 0 && threshold <= 1 && modelVersion != null
                    && modelVersion.matches("[A-Za-z0-9._:-]{1,128}") && isAiGenerated == (score > threshold);
        }
    }
}
