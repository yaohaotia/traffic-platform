package com.yao.traffic.detection;

import com.yao.traffic.shared.security.CurrentActor;
import com.yao.traffic.shared.web.BusinessException;
import com.yao.traffic.shared.web.PageResult;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.List;

@Service
public class DetectionAdministration {
    private final JdbcTemplate jdbc;
    private final DetectionTasks tasks;
    public DetectionAdministration(JdbcTemplate jdbc,DetectionTasks tasks){this.jdbc=jdbc;this.tasks=tasks;}
    @PreAuthorize("hasAuthority('report:submit') or hasAuthority('report:read') or hasAuthority('report:review') or hasAuthority('detection:manage')")
    public TaskView get(String id) {
        var row=task(id,false);
        var actor=CurrentActor.profile();
        boolean owner=actor.userId()==row.userId();
        boolean privileged=actor.permissions().contains("detection:manage") || actor.permissions().contains("report:review");
        if(!owner && !(privileged && inScope(row.regionCode())))throw forbidden();
        return view(row);
    }
    @PreAuthorize("hasAuthority('detection:manage')")
    @Transactional
    public TaskView retry(String id,String reason) {
        validateReason(reason);
        var row=task(id,true);
        if(!inScope(row.regionCode()))throw forbidden();
        if(!List.of("DEAD","RETRY_WAIT").contains(row.status()) || !"WAITING_DETECTION".equals(row.reviewStatus()))
            throw conflict("只能重试失败且尚未人工终审的检测任务");
        jdbc.update("""
                UPDATE detection_task SET status='PENDING',attempt_count=0,version=version+1,lease_token=NULL,lease_until=NULL,
                next_attempt_at=CURRENT_TIMESTAMP(6),last_signal_at=CURRENT_TIMESTAMP(6),last_error_code=NULL,last_error_message=NULL,
                completed_at=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?
                """,id);
        jdbc.update("UPDATE traffic_report SET detection_status='PENDING' WHERE id=?",row.reportId());
        tasks.appendEvent(id,row.reportId(),"DETECTION_REQUESTED");
        jdbc.update("INSERT INTO detection_operation(task_id,operator_id,operation,reason) VALUES(?,?,'MANUAL_RETRY',?)",
                id,CurrentActor.profile().userId(),reason);
        return view(task(id,false));
    }
    @PreAuthorize("hasAuthority('message:manage')")
    public PageResult<OutboxView> outbox(String status,int page,int size) {
        if(!List.of("PENDING","PUBLISHING","SENT","DEAD").contains(status))throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_STATUS","消息状态不正确");
        int offset=PageResult.offset(page,size);
        var actor=CurrentActor.profile();
        List<Object> args=new ArrayList<>();args.add(status);
        String scope="";
        if(!actor.permissions().contains("identity:user:manage")) {
            if(actor.regionCodes().isEmpty())return new PageResult<>(List.of(),page,size);
            scope=" AND r.region_code IN ("+String.join(",",java.util.Collections.nCopies(actor.regionCodes().size(),"?"))+")";
            args.addAll(actor.regionCodes());
        }
        args.add(size);args.add(offset);
        var rows=jdbc.query("""
                SELECT o.event_id,o.aggregate_id,o.event_type,o.status,o.attempt_count,o.last_error_code
                FROM outbox_event o JOIN traffic_report r ON r.id=o.aggregate_id WHERE o.aggregate_type='REPORT' AND o.status=?
                """+scope+" ORDER BY o.created_at,o.event_id LIMIT ? OFFSET ?",(rs,n)->new OutboxView(rs.getString(1),rs.getLong(2),
                rs.getString(3),rs.getString(4),rs.getInt(5),rs.getString(6)),args.toArray());
        return new PageResult<>(rows,page,size);
    }
    @PreAuthorize("hasAuthority('message:manage')")
    @Transactional
    public OutboxView retryOutbox(String eventId,String reason) {
        validateReason(reason);
        var rows=jdbc.query("""
                SELECT o.event_id,o.aggregate_id,o.event_type,o.status,o.attempt_count,o.last_error_code,r.region_code,t.id
                FROM outbox_event o JOIN traffic_report r ON o.aggregate_type='REPORT' AND r.id=o.aggregate_id
                JOIN detection_task t ON t.report_id=r.id WHERE o.event_id=? FOR UPDATE
                """,(rs,n)->new MessageRow(new OutboxView(rs.getString(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getInt(5),rs.getString(6)),
                rs.getString(7),rs.getString(8)),eventId);
        if(rows.isEmpty())throw new BusinessException(HttpStatus.NOT_FOUND,"EVENT_NOT_FOUND","消息不存在");
        var row=rows.getFirst();
        if(!inScope(row.regionCode()))throw forbidden();
        if(!"DEAD".equals(row.view().status()))throw conflict("只能重投已停止自动投递的消息");
        jdbc.update("""
                UPDATE outbox_event SET status='PENDING',attempt_count=0,lease_token=NULL,lease_until=NULL,
                next_attempt_at=CURRENT_TIMESTAMP(6),last_error_code=NULL,last_error_message=NULL,updated_at=CURRENT_TIMESTAMP(6)
                WHERE event_id=?
                """,eventId);
        jdbc.update("INSERT INTO detection_operation(task_id,operator_id,operation,event_id,reason) VALUES(?,?,'OUTBOX_RETRY',?,?)",
                row.taskId(),CurrentActor.profile().userId(),eventId,reason);
        return new OutboxView(eventId,row.view().reportId(),row.view().eventType(),"PENDING",0,null);
    }
    private TaskRow task(String id,boolean lock) {
        var rows=jdbc.query("""
                SELECT t.id,t.report_id,t.status,t.attempt_count,t.version,t.result_score,t.result_threshold,t.model_version,
                t.last_error_code,r.user_id,r.region_code,r.review_status FROM detection_task t JOIN traffic_report r ON r.id=t.report_id
                WHERE t.id=?
                """+(lock?" FOR UPDATE":""),(rs,n)->new TaskRow(rs.getString(1),rs.getLong(2),rs.getString(3),rs.getInt(4),rs.getLong(5),
                (Double)rs.getObject(6),(Double)rs.getObject(7),rs.getString(8),rs.getString(9),rs.getLong(10),rs.getString(11),rs.getString(12)),id);
        return rows.stream().findFirst().orElseThrow(()->new BusinessException(HttpStatus.NOT_FOUND,"TASK_NOT_FOUND","检测任务不存在"));
    }
    private static TaskView view(TaskRow row){return new TaskView(row.id(),row.reportId(),row.status(),row.attemptCount(),row.version(),row.score(),row.threshold(),row.modelVersion(),row.lastErrorCode());}
    private static boolean inScope(String region){var actor=CurrentActor.profile();return actor.permissions().contains("identity:user:manage")||actor.regionCodes().contains(region);}
    private static void validateReason(String reason){if(reason==null||reason.isBlank()||reason.length()>500)throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_REASON","请填写有效操作原因");}
    private static BusinessException forbidden(){return new BusinessException(HttpStatus.FORBIDDEN,"ACCESS_DENIED","无权管理该区域任务");}
    private static BusinessException conflict(String message){return new BusinessException(HttpStatus.CONFLICT,"TASK_STATE_CONFLICT",message);}
    private record TaskRow(String id,long reportId,String status,int attemptCount,long version,Double score,Double threshold,String modelVersion,String lastErrorCode,long userId,String regionCode,String reviewStatus){ }
    private record MessageRow(OutboxView view,String regionCode,String taskId){ }
    public record TaskView(String id,long reportId,String status,int attemptCount,long version,Double score,Double threshold,String modelVersion,String lastErrorCode){ }
    public record OutboxView(String eventId,long reportId,String eventType,String status,int attemptCount,String lastErrorCode){ }
}
