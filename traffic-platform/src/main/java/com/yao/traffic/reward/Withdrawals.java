package com.yao.traffic.reward;

import com.yao.traffic.shared.security.CurrentActor;
import com.yao.traffic.shared.web.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.yao.traffic.reward.RewardLedger.*;

/** Simulated settlement only: no bank details or external payment requests. */
@Service
public class Withdrawals {
    private final RewardLedger ledger;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public Withdrawals(RewardLedger ledger,JdbcTemplate jdbc,PlatformTransactionManager manager){this.ledger=ledger;this.jdbc=jdbc;tx=new TransactionTemplate(manager);}

    @PreAuthorize("hasAuthority('wallet:withdraw')")
    public Withdrawal request(BigDecimal amount,String region,String requestKey){
        money(amount);scope(region);String key=key(requestKey);long user=CurrentActor.profile().userId();
        return tx.execute(status->{
            ledger.wallet(user,true);
            var existing=jdbc.query("SELECT * FROM withdrawal_order WHERE user_id=? AND request_key=? FOR UPDATE",Withdrawals::map,user,key);
            if(!existing.isEmpty()){
                var order=existing.getFirst();
                if(order.amount().compareTo(amount)!=0||!order.regionCode().equals(region))throw conflict("IDEMPOTENCY_CONFLICT","请求号已经用于其他提现申请");
                return order;
            }
            if(recoveryPending(user))
                throw conflict("MANUAL_RECOVERY_PENDING","账户有待人工处理的奖励更正，请先联系管理员");
            String id=UUID.randomUUID().toString();
            ledger.walletChange(user,id,"FREEZE",amount.negate(),amount);
            jdbc.update("INSERT INTO withdrawal_order(id,user_id,region_code,amount,request_key,state) VALUES(?,?,?,?,?,'REQUESTED')",id,user,region,amount,key);
            return required(id,false);
        });
    }

    @PreAuthorize("hasAuthority('withdrawal:manage')")
    public Withdrawal decide(String id,String decision,String reason){
        reason(reason);if(!List.of("APPROVE","REJECT").contains(decision))throw bad("提现审批结果不正确");
        long actor=CurrentActor.profile().userId();
        var source=required(id,false);
        return tx.execute(status->{
            ledger.wallet(source.userId(),true);
            var order=required(id,true);scope(order.regionCode());
            if(actor==order.userId())throw forbidden();
            String target="APPROVE".equals(decision)?"SIMULATED_PAID":"REJECTED";
            if(!"REQUESTED".equals(order.state())){
                if(order.state().equals(target)&&Long.valueOf(actor).equals(order.approverId())&&reason.equals(order.reason()))return order;
                throw conflict("WITHDRAWAL_CONFLICT","提现单已处理");
            }
            if("APPROVE".equals(decision)&&recoveryPending(order.userId()))throw conflict("MANUAL_RECOVERY_PENDING","账户有待人工处理的奖励更正，只可先驳回并释放提现冻结");
            ledger.walletChange(order.userId(),id,"APPROVE".equals(decision)?"SIMULATED_PAYOUT":"UNFREEZE",
                    "APPROVE".equals(decision)?ZERO:order.amount(),order.amount().negate());
            jdbc.update("UPDATE withdrawal_order SET state=?,approver_id=?,reason=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND state='REQUESTED'",target,actor,reason,id);
            return required(id,false);
        });
    }

    @PreAuthorize("hasAuthority('wallet:read')")
    public PageResult<Withdrawal> own(int page,int size){return new PageResult<>(jdbc.query("SELECT * FROM withdrawal_order WHERE user_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
            Withdrawals::map,CurrentActor.profile().userId(),size,PageResult.offset(page,size)),page,size);}

    @PreAuthorize("hasAuthority('withdrawal:manage')")
    public PageResult<Withdrawal> managed(String state,int page,int size){
        int offset=PageResult.offset(page,size);var actor=CurrentActor.profile();
        if(state!=null&&!List.of("REQUESTED","SIMULATED_PAID","REJECTED").contains(state))throw bad("提现状态不正确");
        if(actor.regionCodes().isEmpty())return new PageResult<>(List.of(),page,size);
        var args=new ArrayList<Object>(actor.regionCodes());
        String sql="SELECT * FROM withdrawal_order WHERE region_code IN ("+String.join(",",java.util.Collections.nCopies(args.size(),"?"))+")";
        if(state!=null){sql+=" AND state=?";args.add(state);}
        args.add(size);args.add(offset);
        return new PageResult<>(jdbc.query(sql+" ORDER BY created_at,id LIMIT ? OFFSET ?",Withdrawals::map,args.toArray()),page,size);
    }

    private Withdrawal required(String id,boolean lock){return jdbc.query("SELECT * FROM withdrawal_order WHERE id=?"+(lock?" FOR UPDATE":""),Withdrawals::map,id)
            .stream().findFirst().orElseThrow(RewardLedger::missing);}
    private boolean recoveryPending(long user){return !jdbc.queryForList("SELECT id FROM reward_correction WHERE user_id=? AND state='MANUAL_REQUIRED' FOR UPDATE",String.class,user).isEmpty();}
    private static Withdrawal map(java.sql.ResultSet rs,int n)throws java.sql.SQLException{return new Withdrawal(rs.getString("id"),rs.getLong("user_id"),
            rs.getString("region_code"),rs.getBigDecimal("amount"),rs.getString("state"),rs.getObject("approver_id",Long.class),rs.getString("reason"));}
    public record Withdrawal(String id,long userId,String regionCode,BigDecimal amount,String state,Long approverId,String reason){}
}
