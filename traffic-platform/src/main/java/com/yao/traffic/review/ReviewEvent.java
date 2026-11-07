package com.yao.traffic.review;

import java.util.UUID;

public record ReviewEvent(int schemaVersion,String eventId,String eventType,String taskId,
                          long reportId,long evidenceId,long userId) {
    public boolean valid() {
        try {
            return schemaVersion==1 && "DETECTION_COMPLETED".equals(eventType)
                    && eventId!=null && UUID.fromString(eventId).toString().equals(eventId)
                    && taskId!=null && UUID.fromString(taskId).toString().equals(taskId)
                    && reportId>0 && evidenceId>0 && userId>0;
        } catch(IllegalArgumentException invalid) { return false; }
    }
}
