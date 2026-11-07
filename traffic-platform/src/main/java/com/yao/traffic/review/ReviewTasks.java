package com.yao.traffic.review;

import com.yao.traffic.identity.domain.IdentityProfile;
import com.yao.traffic.evidence.domain.EvidenceContent;
import com.yao.traffic.evidence.infrastructure.EvidenceContentReader;
import com.yao.traffic.evidence.infrastructure.EvidenceRepository;
import com.yao.traffic.identity.infrastructure.IdentityRepository;
import com.yao.traffic.identity.infrastructure.RedisSessionStore;
import com.yao.traffic.shared.security.CurrentActor;
import com.yao.traffic.shared.web.BusinessException;
import com.yao.traffic.shared.web.PageResult;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Review writes consistently lock report before task. External message I/O never runs here. */
@Service
public class ReviewTasks {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ReviewSettings settings;
    private final IdentityRepository identities;
    private final ApprovalPreparationPort approvals;
    private final ObjectMapper json;
    private final EvidenceRepository evidence;
    private final EvidenceContentReader contentReader;
    public ReviewTasks(JdbcTemplate jdbc,PlatformTransactionManager manager,ReviewSettings settings,
                       IdentityRepository identities,ApprovalPreparationPort approvals,ObjectMapper json,
                       EvidenceRepository evidence,EvidenceContentReader contentReader){
        this.jdbc=jdbc;tx=new TransactionTemplate(manager);this.settings=settings;
        // Report/task locks serialize each aggregate. READ_COMMITTED avoids absent-decision
        // gap locks deadlocking independent approvals before either reaches the budget lock.
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.identities=identities;this.approvals=approvals;this.json=json;
        this.evidence=evidence;this.contentReader=contentReader;
    }

    @PreAuthorize("hasAnyAuthority('report:review','review:claim','review:decide','review:recheck','review:manage')")
    public TaskView get(String id){var task=required(id,false);readScope(task,CurrentActor.profile());return view(task);}

    /** All SQL reads are autocommit; no database lock is held while loading image bytes. */
    @PreAuthorize("hasAnyAuthority('review:claim','review:decide','review:recheck','review:manage')")
    public EvidenceContent evidenceContent(String id){
        var task=required(id,false);var report=report(task.reportId(),false);var actor=CurrentActor.profile();
        // Operational overview may be global, but binary evidence always requires an explicit region binding.
        if(!actor.regionCodes().contains(task.region())||report.owner()==actor.userId())throw forbidden();
        if(!actor.permissions().contains("review:manage")){
            if(!"CLAIMED".equals(task.status())||!Objects.equals(task.assignee(),actor.userId())
                    ||task.leaseUntil()==null||!task.leaseUntil().isAfter(databaseNow()))throw forbidden();
            handlingScope(task,report,actor,false);
        }
        Long evidenceId=jdbc.queryForObject("SELECT evidence_id FROM traffic_report WHERE id=?",Long.class,report.id());
        return contentReader.read(evidence.required(evidenceId));
    }

    @PreAuthorize("hasAnyAuthority('report:review','review:claim','review:decide','review:recheck','review:manage')")
    public PageResult<TaskView> list(String status,String stage,int page,int size){
        int offset=PageResult.offset(page,size);
        if(status!=null&&!List.of("READY","CLAIMED","APPROVED","REJECTED","MANUAL_REQUIRED").contains(status))throw bad("INVALID_STATUS","审核状态不正确");
        if(stage!=null&&!List.of("PRIMARY","RECHECK").contains(stage))throw bad("INVALID_STAGE","审核阶段不正确");
        var actor=CurrentActor.profile();boolean global=globalManager(actor);
        boolean regionalManager=actor.permissions().contains("review:manage");
        var sql=new StringBuilder("SELECT t.id FROM review_task t JOIN traffic_report r ON r.id=t.report_id WHERE 1=1");
        var args=new ArrayList<Object>();
        if(!global){
            if(actor.regionCodes().isEmpty())return new PageResult<>(List.of(),page,size);
            sql.append(" AND t.region_code IN (").append(placeholders(actor.regionCodes().size())).append(")");args.addAll(actor.regionCodes());
        }
        // READY is the claim-candidate view for ordinary reviewers. Overview/history is
        // read-only and follows the same regional visibility as get(), not claim eligibility.
        if("READY".equals(status)&&!regionalManager){
            sql.append(" AND r.user_id<>?");args.add(actor.userId());
            if(!actor.permissions().contains("review:claim"))return new PageResult<>(List.of(),page,size);
            boolean primary=actor.permissions().contains("review:decide"),recheck=actor.permissions().contains("review:recheck");
            if(!primary&&!recheck)return new PageResult<>(List.of(),page,size);
            if(!primary)sql.append(" AND t.stage='RECHECK'");
            if(!recheck)sql.append(" AND t.stage='PRIMARY'");
            sql.append(" AND (t.stage<>'RECHECK' OR ((t.primary_reviewer_id IS NULL OR t.primary_reviewer_id<>?)"
                    +" AND NOT EXISTS(SELECT 1 FROM review_decision previous WHERE previous.task_id=t.id AND previous.actor_id=?)))");
            args.add(actor.userId());args.add(actor.userId());
            sql.append(" AND (t.preferred_reviewer_id IS NULL OR t.preferred_reviewer_id=?)");args.add(actor.userId());
        }
        if("CLAIMED".equals(status)&&!regionalManager){sql.append(" AND t.assignee_id=?");args.add(actor.userId());}
        if(status!=null){sql.append(" AND t.status=?");args.add(status);}
        if(stage!=null){sql.append(" AND t.stage=?");args.add(stage);}
        sql.append(" ORDER BY t.created_at,t.id LIMIT ? OFFSET ?");args.add(size);args.add(offset);
        var ids=jdbc.queryForList(sql.toString(),String.class,args.toArray());
        return new PageResult<>(ids.stream().map(id->view(required(id,false))).toList(),page,size);
    }

    @PreAuthorize("hasAuthority('review:claim')")
    public ClaimView claim(String id,long expectedVersion){
        if(expectedVersion<0)throw bad("INVALID_VERSION","版本不正确");
        var actor=CurrentActor.profile();
        return tx.execute(status->{
            var locked=lock(id);var task=locked.task();
            handlingScope(task,locked.report(),actor,true);
            if(!"READY".equals(task.status())||task.version()!=expectedVersion)throw conflict();
            if(task.preferred()!=null&&task.preferred()!=actor.userId())throw forbidden();
            String token=UUID.randomUUID().toString();
            int changed=jdbc.update("""
                    UPDATE review_task SET status='CLAIMED',version=version+1,assignee_id=?,lease_token=?,
                    lease_until=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)),updated_at=CURRENT_TIMESTAMP(6)
                    WHERE id=? AND status='READY' AND version=?
                    """,actor.userId(),token,settings.leaseSeconds,id,expectedVersion);
            if(changed!=1)throw conflict();
            audit(id,"CLAIMED",actor.userId(),task.version(),task.version()+1,"REVIEW_CLAIMED");
            var current=required(id,false);
            return new ClaimView(current.id(),current.reportId(),current.stage(),current.status(),current.version(),
                    actor.userId(),token,current.leaseUntil());
        });
    }

    @PreAuthorize("hasAnyAuthority('review:decide','review:recheck')")
    public TaskView decide(String id,long version,String leaseToken,String decision,String reason){
        validateReason(reason);
        if(version<0||!uuid(leaseToken)||!List.of("APPROVE","REJECT","REQUEST_RECHECK").contains(decision))throw bad("INVALID_DECISION","审核提交内容不正确");
        var actor=CurrentActor.profile();String tokenHash=RedisSessionStore.hash(leaseToken);
        return tx.execute(status->{
            var locked=lock(id);var task=locked.task();var report=locked.report();
            if(!actor.regionCodes().contains(task.region())||report.owner()==actor.userId())throw forbidden();
            var prior=jdbc.query("SELECT actor_id,stage,decision,reason,lease_token_hash FROM review_decision WHERE task_id=? AND task_version=? FOR UPDATE",
                    (rs,n)->new Prior(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5)),id,version);
            if(!prior.isEmpty()){
                var saved=prior.getFirst();
                if(saved.actor()!=actor.userId())throw forbidden();
                requireStagePermission(saved.stage(),actor);
                if(saved.decision().equals(decision)&&saved.reason().equals(reason)&&saved.tokenHash().equals(tokenHash))return view(task);
                throw conflict();
            }
            handlingScope(task,report,actor,false);
            if(!"CLAIMED".equals(task.status())||task.version()!=version||!Objects.equals(task.assignee(),actor.userId())
                    ||!Objects.equals(task.token(),leaseToken)||task.leaseUntil()==null||!task.leaseUntil().isAfter(databaseNow()))throw conflict();
            if("REQUEST_RECHECK".equals(decision)&&!"PRIMARY".equals(task.stage()))throw conflict();
            String decisionId=UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO review_decision(id,task_id,task_version,stage,actor_id,decision,reason,lease_token_hash)
                    VALUES(?,?,?,?,?,?,?,?)
                    """,decisionId,id,version,task.stage(),actor.userId(),decision,reason,tokenHash);
            String operation,eventType;
            if("REQUEST_RECHECK".equals(decision)){
                int updated=jdbc.update("""
                        UPDATE review_task SET stage='RECHECK',status='READY',version=version+1,primary_reviewer_id=?,
                        assignee_id=NULL,preferred_reviewer_id=NULL,lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6)
                        WHERE id=? AND version=? AND status='CLAIMED' AND assignee_id=? AND lease_token=?
                        AND lease_until>CURRENT_TIMESTAMP(6)
                        """,actor.userId(),id,version,actor.userId(),leaseToken);
                if(updated!=1)throw conflict();
                jdbc.update("UPDATE traffic_report SET review_status='RISK_REVIEW' WHERE id=?",report.id());
                operation="ESCALATED";eventType="REVIEW_ESCALATED";
            }else{
                String finalState="APPROVE".equals(decision)?"APPROVED":"REJECTED";
                if("APPROVE".equals(decision))approvals.prepare(new ApprovalPreparationPort.ApprovedDecision(decisionId,id,version,
                        report.id(),actor.userId(),report.campaignId(),report.owner(),report.amount()));
                int updated=jdbc.update("""
                        UPDATE review_task SET status=?,version=version+1,lease_token=NULL,lease_until=NULL,
                        preferred_reviewer_id=NULL,decided_at=CURRENT_TIMESTAMP(6),updated_at=CURRENT_TIMESTAMP(6)
                        WHERE id=? AND version=? AND status='CLAIMED' AND assignee_id=? AND lease_token=?
                        AND lease_until>CURRENT_TIMESTAMP(6)
                        """,finalState,id,version,actor.userId(),leaseToken);
                if(updated!=1)throw conflict();
                jdbc.update("UPDATE traffic_report SET review_status=? WHERE id=?",finalState,report.id());
                jdbc.update("UPDATE review_appeal SET status='RESOLVED',resolved_at=CURRENT_TIMESTAMP(6) WHERE report_id=? AND status='OPEN'",report.id());
                operation=finalState;eventType="APPROVE".equals(decision)?"REVIEW_APPROVED":"REVIEW_REJECTED";
            }
            audit(id,operation,actor.userId(),version,version+1,reason);
            appendDecisionEvent(task,report,decisionId,eventType,decision,version+1);
            return view(required(id,false));
        });
    }

    @PreAuthorize("hasAuthority('review:manage')")
    public TaskView reassign(String id,long expectedVersion,long targetReviewerId,String reason){
        validateReason(reason);if(expectedVersion<0||targetReviewerId<=0)throw bad("INVALID_REASSIGNMENT","转派参数不正确");
        var actor=CurrentActor.profile();
        return tx.execute(status->{
            var locked=lock(id);var task=locked.task();
            readScope(task,actor);
            if(terminal(task.status())||task.version()!=expectedVersion)throw conflict();
            var target=identities.findById(targetReviewerId).filter(user->user.enabled()).orElseThrow(ReviewTasks::forbidden);
            handlingScope(task,locked.report(),identities.profile(target),true);
            jdbc.update("""
                    UPDATE review_task SET status='READY',version=version+1,assignee_id=NULL,preferred_reviewer_id=?,
                    lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND version=?
                    """,targetReviewerId,id,expectedVersion);
            audit(id,"TRANSFERRED",actor.userId(),expectedVersion,expectedVersion+1,reason);
            return view(required(id,false));
        });
    }

    @PreAuthorize("hasAnyAuthority('report:read','report:submit')")
    public AppealView appeal(long reportId,String reason){
        validateReason(reason);var actor=CurrentActor.profile();
        return tx.execute(status->{
            var report=report(reportId,true);
            if(report.owner()!=actor.userId())throw forbidden();
            var tasks=jdbc.queryForList("SELECT id FROM review_task WHERE report_id=?",String.class,reportId);
            if(tasks.isEmpty())throw conflict();
            var task=required(tasks.getFirst(),true);
            if(!"REJECTED".equals(report.reviewStatus())||!"REJECTED".equals(task.status())||!"NONE".equals(report.rewardStatus()))throw conflict();
            if(jdbc.queryForObject("SELECT COUNT(*) FROM review_appeal WHERE report_id=?",Integer.class,reportId)!=0)throw conflict();
            var finalActors=jdbc.queryForList("SELECT actor_id FROM review_decision WHERE task_id=? AND decision='REJECT' ORDER BY task_version DESC LIMIT 1",Long.class,task.id());
            if(finalActors.isEmpty())throw conflict();
            String appealId=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO review_appeal(id,report_id,task_id,appellant_id,reason,status) VALUES(?,?,?,?,?,'OPEN')",
                    appealId,reportId,task.id(),actor.userId(),reason);
            jdbc.update("""
                    UPDATE review_task SET stage='RECHECK',status='READY',version=version+1,primary_reviewer_id=?,
                    assignee_id=NULL,preferred_reviewer_id=NULL,lease_token=NULL,lease_until=NULL,decided_at=NULL,
                    updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND version=?
                    """,finalActors.getFirst(),task.id(),task.version());
            jdbc.update("UPDATE traffic_report SET review_status='RISK_REVIEW' WHERE id=?",reportId);
            audit(task.id(),"APPEALED",actor.userId(),task.version(),task.version()+1,reason);
            return new AppealView(appealId,reportId,"OPEN","RISK_REVIEW");
        });
    }

    @PreAuthorize("hasAnyAuthority('report:review','review:claim','review:decide','review:recheck','review:manage')")
    public PageResult<AuditView> audits(String id,int page,int size){
        int offset=PageResult.offset(page,size);readScope(required(id,false),CurrentActor.profile());
        var items=jdbc.query("SELECT * FROM review_audit WHERE task_id=? ORDER BY id LIMIT ? OFFSET ?",
                (rs,n)->new AuditView(rs.getLong("id"),rs.getString("operation"),rs.getObject("actor_id",Long.class),
                        rs.getLong("from_version"),rs.getLong("to_version"),rs.getString("reason"),rs.getTimestamp("created_at").toInstant()),id,size,offset);
        return new PageResult<>(items,page,size);
    }

    /** Shared by the local scheduler and, in P7, XXL-JOB. Each candidate is a short transaction. */
    public int recoverExpired(){
        var ids=jdbc.queryForList("SELECT id FROM review_task WHERE status='CLAIMED' AND lease_until<=CURRENT_TIMESTAMP(6) ORDER BY lease_until LIMIT 100",String.class);
        int changed=0;
        for(String id:ids){
            boolean recovered=Boolean.TRUE.equals(tx.execute(status->{
                var locked=lock(id);var task=locked.task();
                if(!"CLAIMED".equals(task.status())||task.leaseUntil()==null||task.leaseUntil().isAfter(databaseNow()))return false;
                int transfers=task.transfers()+1;String next=transfers>=settings.maxTransfers?"MANUAL_REQUIRED":"READY";
                int updated=jdbc.update("""
                        UPDATE review_task SET status=?,version=version+1,transfer_count=?,assignee_id=NULL,preferred_reviewer_id=NULL,
                        lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND version=? AND status='CLAIMED'
                        AND lease_until<=CURRENT_TIMESTAMP(6)
                        """,next,transfers,id,task.version());
                if(updated==0)return false;
                audit(id,"MANUAL_REQUIRED".equals(next)?"MANUAL_REQUIRED":"EXPIRED",null,task.version(),task.version()+1,"REVIEW_LEASE_EXPIRED");
                return true;
            }));
            if(recovered)changed++;
        }
        return changed;
    }

    private Locked lock(String id){
        var ids=jdbc.queryForList("SELECT report_id FROM review_task WHERE id=?",Long.class,id);
        if(ids.isEmpty())throw missing();
        var report=report(ids.getFirst(),true);return new Locked(required(id,true),report);
    }
    private Task required(String id,boolean lock){
        return jdbc.query("SELECT * FROM review_task WHERE id=?"+(lock?" FOR UPDATE":""),(rs,n)->new Task(
                rs.getString("id"),rs.getLong("report_id"),rs.getString("region_code"),rs.getString("stage"),rs.getString("status"),
                rs.getLong("version"),rs.getObject("assignee_id",Long.class),rs.getObject("preferred_reviewer_id",Long.class),
                rs.getString("lease_token"),instant(rs.getTimestamp("lease_until")),rs.getObject("primary_reviewer_id",Long.class),
                rs.getInt("transfer_count"),rs.getLong("source_detection_version")),id).stream().findFirst().orElseThrow(ReviewTasks::missing);
    }
    private Report report(long id,boolean lock){
        return jdbc.query("SELECT id,user_id,campaign_id,reward_amount,review_status,reward_status FROM traffic_report WHERE id=?"+(lock?" FOR UPDATE":""),
                (rs,n)->new Report(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getBigDecimal(4),rs.getString(5),rs.getString(6)),id)
                .stream().findFirst().orElseThrow(ReviewTasks::missing);
    }
    private TaskView view(Task task){
        var results=jdbc.query("SELECT result_score,result_threshold,model_version FROM detection_task WHERE report_id=?",
                (rs,n)->new Risk(rs.getObject(1,Double.class),rs.getObject(2,Double.class),rs.getString(3)),task.reportId());
        Risk risk=results.isEmpty()?new Risk(null,null,null):results.getFirst();
        return new TaskView(task.id(),task.reportId(),task.region(),task.stage(),task.status(),task.version(),task.assignee(),
                task.preferred(),task.leaseUntil(),task.primary(),task.transfers(),task.sourceVersion(),risk.score(),risk.threshold(),risk.model());
    }
    private void readScope(Task task,IdentityProfile actor){
        if(!actor.regionCodes().contains(task.region())&&!globalManager(actor))throw forbidden();
    }
    private void handlingScope(Task task,Report report,IdentityProfile actor,boolean requireClaim){
        if(!actor.regionCodes().contains(task.region())||report.owner()==actor.userId())throw forbidden();
        if(!("PRIMARY".equals(task.stage())?"WAITING_REVIEW":"RISK_REVIEW").equals(report.reviewStatus()))throw conflict();
        if(requireClaim&&!actor.permissions().contains("review:claim"))throw forbidden();
        requireStagePermission(task.stage(),actor);
        if("RECHECK".equals(task.stage())&&(Objects.equals(task.primary(),actor.userId())
                ||jdbc.queryForObject("SELECT COUNT(*) FROM review_decision WHERE task_id=? AND actor_id=?",Integer.class,task.id(),actor.userId())!=0))throw forbidden();
    }
    private void requireStagePermission(String stage,IdentityProfile actor){
        if(!actor.permissions().contains("RECHECK".equals(stage)?"review:recheck":"review:decide"))throw forbidden();
    }
    private void audit(String task,String operation,Long actor,long from,long to,String reason){
        jdbc.update("INSERT INTO review_audit(task_id,operation,actor_id,from_version,to_version,reason) VALUES(?,?,?,?,?,?)",task,operation,actor,from,to,reason);
    }
    private void appendDecisionEvent(Task task,Report report,String decisionId,String type,String decision,long version){
        String id=UUID.randomUUID().toString();var payload=new java.util.LinkedHashMap<String,Object>();
        payload.put("schemaVersion",1);payload.put("eventId",id);payload.put("eventType",type);payload.put("taskId",task.id());
        payload.put("reportId",report.id());payload.put("decisionId",decisionId);payload.put("reviewVersion",version);payload.put("decision",decision);
        jdbc.update("INSERT INTO outbox_event(event_id,aggregate_type,aggregate_id,event_type,payload,status,attempt_count) VALUES(?,'REPORT',?,?,?,'PENDING',0)",
                id,report.id(),type,json.writeValueAsString(payload));
    }
    private Instant databaseNow(){return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)",Timestamp.class).toInstant();}
    private static Instant instant(Timestamp value){return value==null?null:value.toInstant();}
    private static boolean globalManager(IdentityProfile actor){return actor.permissions().contains("review:manage")&&actor.permissions().contains("identity:user:manage");}
    private static boolean terminal(String status){return "APPROVED".equals(status)||"REJECTED".equals(status);}
    private static String placeholders(int count){return String.join(",",java.util.Collections.nCopies(count,"?"));}
    private static boolean uuid(String value){try{return value!=null&&UUID.fromString(value).toString().equals(value);}catch(IllegalArgumentException invalid){return false;}}
    private static void validateReason(String reason){if(reason==null||reason.isBlank()||reason.length()>500)throw bad("INVALID_REASON","请填写不超过500字的原因");}
    private static BusinessException bad(String code,String message){return new BusinessException(HttpStatus.BAD_REQUEST,code,message);}
    private static BusinessException forbidden(){return new BusinessException(HttpStatus.FORBIDDEN,"ACCESS_DENIED","无权处理此审核工单");}
    private static BusinessException conflict(){return new BusinessException(HttpStatus.CONFLICT,"REVIEW_CONFLICT","工单版本、租约或状态已变化");}
    private static BusinessException missing(){return new BusinessException(HttpStatus.NOT_FOUND,"REVIEW_NOT_FOUND","审核工单不存在");}
    private record Task(String id,long reportId,String region,String stage,String status,long version,Long assignee,Long preferred,String token,Instant leaseUntil,Long primary,int transfers,long sourceVersion){ }
    private record Report(long id,long owner,long campaignId,BigDecimal amount,String reviewStatus,String rewardStatus){ }
    private record Locked(Task task,Report report){ }
    private record Prior(long actor,String stage,String decision,String reason,String tokenHash){ }
    private record Risk(Double score,Double threshold,String model){ }
    public record TaskView(String id,long reportId,String regionCode,String stage,String status,long version,Long assigneeId,Long preferredReviewerId,
                           Instant leaseUntil,Long primaryReviewerId,int transferCount,long sourceDetectionVersion,Double score,Double threshold,String modelVersion){ }
    public record ClaimView(String id,long reportId,String stage,String status,long version,long assigneeId,String leaseToken,Instant leaseUntil){ }
    public record AuditView(long id,String operation,Long actorId,long fromVersion,long toVersion,String reason,Instant createdAt){ }
    public record AppealView(String id,long reportId,String status,String reviewStatus){ }
}
