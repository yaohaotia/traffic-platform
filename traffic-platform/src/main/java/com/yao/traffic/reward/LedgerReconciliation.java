package com.yao.traffic.reward;

import com.yao.traffic.shared.security.CurrentActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Consistent repeatable-read snapshot, no repair-by-overwrite. Drift requires investigation. */
@Service
public class LedgerReconciliation {
    private final RewardLedger ledger;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate read;
    public LedgerReconciliation(RewardLedger ledger,JdbcTemplate jdbc,PlatformTransactionManager manager){
        this.ledger=ledger;this.jdbc=jdbc;read=new TransactionTemplate(manager);read.setReadOnly(true);
        read.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    @PreAuthorize("hasAuthority('reward:manage')")
    public Check campaign(long id){
        RewardLedger.scope(ledger.budget(id,false).region());return read.execute(status->campaignCheck(id));
    }
    @PreAuthorize("hasAuthority('wallet:read')")
    public Check ownWallet(){return read.execute(status->walletCheck(CurrentActor.profile().userId()));}
    public Sweep sweep(long afterCampaign,long afterUser){
        return read.execute(status->{
            var campaigns=jdbc.queryForList("SELECT id FROM campaign WHERE id>? ORDER BY id LIMIT 100",Long.class,afterCampaign);
            var users=jdbc.queryForList("SELECT user_id FROM wallet_account WHERE user_id>? ORDER BY user_id LIMIT 100",Long.class,afterUser);
            var checks=new ArrayList<Check>();campaigns.forEach(id->checks.add(campaignCheck(id)));users.forEach(id->checks.add(walletCheck(id)));
            return new Sweep(checks,campaigns.isEmpty()?afterCampaign:campaigns.getLast(),users.isEmpty()?afterUser:users.getLast(),campaigns.size()==100||users.size()==100);
        });
    }
    private Check campaignCheck(long id){
        var budget=ledger.budget(id,false);
        BigDecimal reserved=sum("SELECT COALESCE(SUM(amount),0) FROM reward_order WHERE campaign_id=? AND state='RESERVED'",id);
        BigDecimal spent=sum("SELECT COALESCE(SUM(amount),0) FROM reward_order WHERE campaign_id=? AND state='CREDITED'",id);
        boolean matches=equal(budget.reserved(),reserved)&&equal(budget.spent(),spent)
                &&equal(budget.reserved(),sum("SELECT COALESCE(SUM(reserved_delta),0) FROM budget_entry WHERE campaign_id=?",id))
                &&equal(budget.spent(),sum("SELECT COALESCE(SUM(spent_delta),0) FROM budget_entry WHERE campaign_id=?",id));
        return new Check("CAMPAIGN",id,matches,budget.reserved(),reserved,budget.spent(),spent);
    }
    private Check walletCheck(long user){
        var wallet=ledger.wallet(user,false);
        BigDecimal available=sum("SELECT COALESCE(SUM(available_delta),0) FROM wallet_entry WHERE user_id=?",user);
        BigDecimal frozen=sum("SELECT COALESCE(SUM(frozen_delta),0) FROM wallet_entry WHERE user_id=?",user);
        BigDecimal requested=sum("SELECT COALESCE(SUM(amount),0) FROM withdrawal_order WHERE user_id=? AND state='REQUESTED'",user);
        boolean matches=equal(wallet.available(),available)&&equal(wallet.frozen(),frozen)&&equal(wallet.frozen(),requested);
        // Independent business totals catch a missing entry even if a corrupted balance was changed to match it.
        BigDecimal credits=sum("SELECT COALESCE(SUM(amount),0) FROM reward_order WHERE user_id=? AND state IN ('CREDITED','REVERSED')",user);
        BigDecimal reversals=sum("SELECT COALESCE(SUM(amount),0) FROM reward_order WHERE user_id=? AND state='REVERSED'",user);
        BigDecimal paid=sum("SELECT COALESCE(SUM(amount),0) FROM withdrawal_order WHERE user_id=? AND state='SIMULATED_PAID'",user);
        matches&=equal(wallet.available().add(wallet.frozen()),credits.subtract(reversals).subtract(paid));
        return new Check("WALLET",user,matches,wallet.available(),available,wallet.frozen(),frozen);
    }
    private BigDecimal sum(String sql,long id){return jdbc.queryForObject(sql,BigDecimal.class,id);}
    private boolean equal(BigDecimal a,BigDecimal b){return a.compareTo(b)==0;}
    public record Check(String type,long id,boolean matches,BigDecimal actualFirst,BigDecimal expectedFirst,BigDecimal actualSecond,BigDecimal expectedSecond){}
    public record Sweep(List<Check> checks,long nextCampaign,long nextUser,boolean hasMore){}
}
