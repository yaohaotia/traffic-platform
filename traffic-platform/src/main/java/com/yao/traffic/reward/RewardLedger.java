package com.yao.traffic.reward;

import com.yao.traffic.review.ApprovalPreparationPort.ApprovedDecision;
import com.yao.traffic.shared.security.CurrentActor;
import com.yao.traffic.shared.web.BusinessException;
import com.yao.traffic.shared.web.PageResult;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import com.yao.traffic.operations.CampaignCatalog;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Monetary truth lives in MySQL. Every state change and its immutable ledger entry share a transaction. */
@Service
public class RewardLedger {
    static final BigDecimal ZERO=new BigDecimal("0.00");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final ObjectProvider<CampaignCatalog> catalog;
    public RewardLedger(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper json,ObjectProvider<CampaignCatalog> catalog){
        this.jdbc=jdbc;tx=new TransactionTemplate(manager);this.json=json;this.catalog=catalog;
    }

    /** Caller already holds report/review locks and has inserted the immutable approval decision. */
    public void reserve(ApprovedDecision decision){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Existing review transaction required");
        money(decision.amount());
        var budget=budget(decision.campaignId(),true);
        int changed=jdbc.update("""
                UPDATE campaign SET budget_reserved=budget_reserved+? WHERE id=?
                AND budget_amount-budget_reserved-budget_spent>=?
                """,decision.amount(),decision.campaignId(),decision.amount());
        if(changed!=1)throw conflict("BUDGET_INSUFFICIENT","任务可用预算不足，请追加预算后重新审核");
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO reward_order(id,report_id,decision_id,campaign_id,user_id,amount,state) VALUES(?,?,?,?,?,?,'RESERVED')",
                id,decision.reportId(),decision.decisionId(),decision.campaignId(),decision.userId(),decision.amount());
        budgetEntry(decision.campaignId(),id,"RESERVE",ZERO,decision.amount(),ZERO,decision.reviewerId(),"审核通过预留预算");
        jdbc.update("UPDATE review_approval_intent SET status='INTEGRATED' WHERE decision_id=?",decision.decisionId());
        jdbc.update("UPDATE traffic_report SET reward_status='RESERVED' WHERE id=?",decision.reportId());
        event("REWARD_RESERVED",id,decision.reportId());
    }

    /** Locks report -> order -> campaign -> wallet, same order as compensation. */
    boolean credit(String id){
        return Boolean.TRUE.equals(tx.execute(status->{
            Order order=lockedOrder(id);
            if(!"RESERVED".equals(order.state()))return true; // cancellation/reversal and duplicate signals cannot resurrect an order
            var states=jdbc.queryForMap("SELECT review_status,reward_status FROM traffic_report WHERE id=?",order.reportId());
            if(!"APPROVED".equals(states.get("review_status")))throw conflict("REWARD_NOT_APPROVED","奖励尚未满足审核条件");
            budget(order.campaignId(),true);
            wallet(order.userId(),true);
            int changed=jdbc.update("UPDATE campaign SET budget_reserved=budget_reserved-?,budget_spent=budget_spent+? WHERE id=? AND budget_reserved>=?",
                    order.amount(),order.amount(),order.campaignId(),order.amount());
            if(changed!=1)throw new IllegalStateException("Reserved budget is inconsistent");
            walletChange(order.userId(),id,"CREDIT",order.amount(),ZERO);
            budgetEntry(order.campaignId(),id,"SPEND",ZERO,order.amount().negate(),order.amount(),null,"奖励入账");
            jdbc.update("UPDATE reward_order SET state='CREDITED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND state='RESERVED'",id);
            jdbc.update("UPDATE traffic_report SET reward_status='CREDITED' WHERE id=?",order.reportId());
            event("REWARD_CREDITED",id,order.reportId());return true;
        }));
    }

    @PreAuthorize("hasAuthority('reward:manage')")
    public Order activate(String intentId,String reason){
        reason(reason);long actor=CurrentActor.profile().userId();
        return tx.execute(status->{
            var rows=jdbc.queryForList("SELECT report_id FROM review_approval_intent WHERE id=?",Long.class,intentId);
            if(rows.isEmpty())throw missing();
            jdbc.queryForMap("SELECT id FROM traffic_report WHERE id=? FOR UPDATE",rows.getFirst());
            var source=jdbc.queryForMap("SELECT * FROM review_approval_intent WHERE id=? FOR UPDATE",intentId);
            long campaign=((Number)source.get("campaign_id")).longValue();scope(budget(campaign,false).region());
            var existing=jdbc.query("SELECT * FROM reward_order WHERE report_id=? FOR UPDATE",RewardLedger::order,rows.getFirst());
            if(!existing.isEmpty())return existing.getFirst();
            String task=(String)source.get("task_id");
            var decision=jdbc.queryForMap("SELECT d.decision,t.status FROM review_decision d JOIN review_task t ON t.id=d.task_id WHERE d.id=? AND t.id=? FOR UPDATE",
                    source.get("decision_id"),task);
            if(!"AWAITING_REWARD_INTEGRATION".equals(source.get("status"))||!"APPROVE".equals(decision.get("decision"))||!"APPROVED".equals(decision.get("status")))
                throw conflict("ACTIVATION_CONFLICT","历史批准意图不满足激活条件");
            reserve(new ApprovedDecision((String)source.get("decision_id"),task,((Number)source.get("task_version")).longValue(),
                    rows.getFirst(),actor,campaign,((Number)source.get("user_id")).longValue(),(BigDecimal)source.get("amount")));
            Order created=jdbc.query("SELECT * FROM reward_order WHERE report_id=?",RewardLedger::order,rows.getFirst()).getFirst();
            jdbc.update("INSERT INTO reward_activation(intent_id,order_id,actor_id,reason) VALUES(?,?,?,?)",intentId,created.id(),actor,reason);
            return created;
        });
    }

    @PreAuthorize("hasAuthority('reward:manage')")
    public Correction correct(String id,String reason){
        reason(reason);long actor=CurrentActor.profile().userId();
        return tx.execute(status->{
            Order order=lockedOrder(id);scope(budget(order.campaignId(),false).region());
            if(order.userId()==actor)throw forbidden();
            budget(order.campaignId(),true);
            Wallet lockedBalance=wallet(order.userId(),true);
            var saved=jdbc.query("SELECT * FROM reward_correction WHERE order_id=? FOR UPDATE",(rs,n)->
                    new Correction(rs.getString("id"),id,rs.getString("state"),rs.getString("reason")),id);
            if(!saved.isEmpty()&&!saved.getFirst().reason().equals(reason))throw conflict("CORRECTION_CONFLICT","更正原因与已保存记录不一致");
            if(!saved.isEmpty()&&"APPLIED".equals(saved.getFirst().state()))return saved.getFirst();
            String correction=saved.isEmpty()?UUID.randomUUID().toString():saved.getFirst().id();
            String state="APPLIED";
            if("RESERVED".equals(order.state())){
                jdbc.update("UPDATE campaign SET budget_reserved=budget_reserved-? WHERE id=?",order.amount(),order.campaignId());
                budgetEntry(order.campaignId(),id,"RELEASE",ZERO,order.amount().negate(),ZERO,actor,reason);
                jdbc.update("UPDATE reward_order SET state='CANCELLED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",id);
                jdbc.update("UPDATE traffic_report SET reward_status='CANCELLED' WHERE id=?",order.reportId());
            }else if("CREDITED".equals(order.state())){
                if(lockedBalance.available().compareTo(order.amount())<0)state="MANUAL_REQUIRED";
                else{
                    walletChange(order.userId(),id,"REVERSAL",order.amount().negate(),ZERO);
                    if(jdbc.update("UPDATE campaign SET budget_spent=budget_spent-? WHERE id=? AND budget_spent>=?",order.amount(),order.campaignId(),order.amount())!=1)
                        throw new IllegalStateException("Spent budget is inconsistent");
                    budgetEntry(order.campaignId(),id,"REFUND",ZERO,ZERO,order.amount().negate(),actor,reason);
                    jdbc.update("UPDATE reward_order SET state='REVERSED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",id);
                    jdbc.update("UPDATE traffic_report SET reward_status='REVERSED' WHERE id=?",order.reportId());
                }
            }else throw conflict("CORRECTION_CONFLICT","订单已经更正");
            if(saved.isEmpty())jdbc.update("INSERT INTO reward_correction(id,order_id,user_id,actor_id,reason,state) VALUES(?,?,?,?,?,?)",correction,id,order.userId(),actor,reason,state);
            else jdbc.update("UPDATE reward_correction SET state=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",state,correction);
            if("APPLIED".equals(state))event("REWARD_CORRECTED",id,order.reportId());
            return new Correction(correction,id,state,reason);
        });
    }

    @PreAuthorize("hasAuthority('reward:manage')")
    public Budget addBudget(long campaign,BigDecimal amount,String requestKey,String reason){
        money(amount);reason(reason);String key=key(requestKey);long actor=CurrentActor.profile().userId();
        return tx.execute(status->{
            Budget budget=budget(campaign,true);scope(budget.region());
            var prior=jdbc.queryForList("SELECT total_delta,reason FROM budget_entry WHERE campaign_id=? AND source_key=? AND kind='TOP_UP' FOR UPDATE",campaign,key);
            if(!prior.isEmpty()){
                if(((BigDecimal)prior.getFirst().get("total_delta")).compareTo(amount)!=0||!reason.equals(prior.getFirst().get("reason")))
                    throw conflict("IDEMPOTENCY_CONFLICT","请求号已经用于其他预算操作");
                return budget;
            }
            if(budget.total().add(amount).compareTo(new BigDecimal("9999999999.99"))>0)throw bad("金额超出上限");
            jdbc.update("UPDATE campaign SET budget_amount=budget_amount+? WHERE id=?",amount,campaign);
            catalog.ifAvailable(cache->cache.invalidateAfterCommit(campaign));
            budgetEntry(campaign,key,"TOP_UP",amount,ZERO,ZERO,actor,reason);return budget(campaign,false);
        });
    }

    @PreAuthorize("hasAuthority('reward:manage')")
    public Budget budgetView(long id){var budget=budget(id,false);scope(budget.region());return budget;}
    @PreAuthorize("hasAuthority('wallet:read')")
    public Wallet ownWallet(){return wallet(CurrentActor.profile().userId(),false);}
    @PreAuthorize("hasAuthority('wallet:read')")
    public PageResult<Map<String,Object>> entries(int page,int size){return new PageResult<>(jdbc.queryForList(
            "SELECT id,source_id,kind,available_delta,frozen_delta,created_at FROM wallet_entry WHERE user_id=? ORDER BY id DESC LIMIT ? OFFSET ?",
            CurrentActor.profile().userId(),size,PageResult.offset(page,size)),page,size);}
    @PreAuthorize("hasAuthority('wallet:read')")
    public PageResult<Order> orders(int page,int size){return new PageResult<>(jdbc.query("SELECT * FROM reward_order WHERE user_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
            RewardLedger::order,CurrentActor.profile().userId(),size,PageResult.offset(page,size)),page,size);}

    Budget budget(long id,boolean lock){return jdbc.query("SELECT * FROM campaign WHERE id=?"+(lock?" FOR UPDATE":""),(rs,n)->
            new Budget(id,rs.getString("region_code"),rs.getBigDecimal("budget_amount"),rs.getBigDecimal("budget_reserved"),rs.getBigDecimal("budget_spent")),id)
            .stream().findFirst().orElseThrow(RewardLedger::missing);}
    Order lockedOrder(String id){
        var reports=jdbc.queryForList("SELECT report_id FROM reward_order WHERE id=?",Long.class,id);
        if(reports.isEmpty())throw missing();
        jdbc.queryForMap("SELECT id FROM traffic_report WHERE id=? FOR UPDATE",reports.getFirst());
        return jdbc.query("SELECT * FROM reward_order WHERE id=? FOR UPDATE",RewardLedger::order,id).getFirst();
    }
    Wallet wallet(long user,boolean lock){
        if(lock)jdbc.update("INSERT INTO wallet_account(user_id,available,frozen,version) VALUES(?,0,0,0) ON DUPLICATE KEY UPDATE user_id=user_id",user);
        return jdbc.query("SELECT * FROM wallet_account WHERE user_id=?"+(lock?" FOR UPDATE":""),(rs,n)->
                new Wallet(user,rs.getBigDecimal("available"),rs.getBigDecimal("frozen"),rs.getLong("version")),user)
                .stream().findFirst().orElse(new Wallet(user,ZERO,ZERO,0));
    }
    void walletChange(long user,String source,String kind,BigDecimal available,BigDecimal frozen){
        int changed=jdbc.update("UPDATE wallet_account SET available=available+?,frozen=frozen+?,version=version+1 WHERE user_id=? AND available+?>=0 AND frozen+?>=0",
                available,frozen,user,available,frozen);
        if(changed!=1)throw conflict("BALANCE_INSUFFICIENT","可用或冻结余额不足");
        jdbc.update("INSERT INTO wallet_entry(user_id,source_id,kind,available_delta,frozen_delta) VALUES(?,?,?,?,?)",user,source,kind,available,frozen);
    }
    private void budgetEntry(long campaign,String source,String kind,BigDecimal total,BigDecimal reserved,BigDecimal spent,Long actor,String reason){
        jdbc.update("INSERT INTO budget_entry(campaign_id,source_key,kind,total_delta,reserved_delta,spent_delta,actor_id,reason) VALUES(?,?,?,?,?,?,?,?)",
                campaign,source,kind,total,reserved,spent,actor,reason);
    }
    void event(String type,String order,long report){
        String id=UUID.randomUUID().toString();
        var payload=Map.of("schemaVersion",1,"eventId",id,"eventType",type,"orderId",order,"reportId",report);
        jdbc.update("INSERT INTO outbox_event(event_id,aggregate_type,aggregate_id,event_type,payload,status,attempt_count) VALUES(?,'REPORT',?,?,?,'PENDING',0)",
                id,report,type,json.writeValueAsString(payload));
    }
    static Order order(java.sql.ResultSet rs,int row)throws java.sql.SQLException{return new Order(rs.getString("id"),rs.getLong("report_id"),
            rs.getString("decision_id"),rs.getLong("campaign_id"),rs.getLong("user_id"),rs.getBigDecimal("amount"),rs.getString("state"));}
    static void money(BigDecimal amount){if(amount==null||amount.signum()<=0||amount.scale()>2||amount.compareTo(new BigDecimal("9999999999.99"))>0)throw bad("金额须大于零且最多两位小数");}
    static void reason(String reason){if(reason==null||reason.isBlank()||reason.length()>500)throw bad("请填写不超过500字的原因");}
    static String key(String key){if(key==null||key.isBlank()||key.length()>128)throw bad("请提供有效的 Idempotency-Key");return com.yao.traffic.identity.infrastructure.RedisSessionStore.hash(key);}
    static void scope(String region){if(!CurrentActor.profile().regionCodes().contains(region))throw forbidden();}
    static BusinessException conflict(String code,String message){return new BusinessException(HttpStatus.CONFLICT,code,message);}
    static BusinessException bad(String message){return new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_REWARD_REQUEST",message);}
    static BusinessException forbidden(){return new BusinessException(HttpStatus.FORBIDDEN,"ACCESS_DENIED","无权执行此账务操作");}
    static BusinessException missing(){return new BusinessException(HttpStatus.NOT_FOUND,"REWARD_NOT_FOUND","账务记录不存在");}
    public record Order(String id,long reportId,String decisionId,long campaignId,long userId,BigDecimal amount,String state){}
    public record Wallet(long userId,BigDecimal available,BigDecimal frozen,long version){}
    public record Budget(long campaignId,String region,BigDecimal total,BigDecimal reserved,BigDecimal spent){public BigDecimal available(){return total.subtract(reserved).subtract(spent);}}
    public record Correction(String id,String orderId,String state,String reason){}
}
