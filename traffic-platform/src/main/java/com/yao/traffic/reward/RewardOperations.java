package com.yao.traffic.reward;

import com.yao.traffic.shared.security.CurrentActor;
import com.yao.traffic.shared.web.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@PreAuthorize("hasAuthority('reward:manage')")
public class RewardOperations {
    private final JdbcTemplate jdbc;
    public RewardOperations(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public PageResult<RewardLedger.Order> orders(int page,int size){
        int offset=PageResult.offset(page,size);var regions=CurrentActor.profile().regionCodes();if(regions.isEmpty())return new PageResult<>(List.of(),page,size);
        var args=new ArrayList<Object>(regions);args.add(size);args.add(offset);
        return new PageResult<>(jdbc.query("SELECT o.* FROM reward_order o JOIN campaign c ON c.id=o.campaign_id WHERE c.region_code IN ("+placeholders(regions.size())+") ORDER BY o.created_at DESC,o.id LIMIT ? OFFSET ?",RewardLedger::order,args.toArray()),page,size);
    }
    public PageResult<Map<String,Object>> intents(int page,int size){
        int offset=PageResult.offset(page,size);var regions=CurrentActor.profile().regionCodes();if(regions.isEmpty())return new PageResult<>(List.of(),page,size);
        var args=new ArrayList<Object>(regions);args.add(size);args.add(offset);
        return new PageResult<>(jdbc.queryForList("SELECT i.id,i.report_id,i.user_id,i.amount,i.status FROM review_approval_intent i JOIN campaign c ON c.id=i.campaign_id WHERE i.status='AWAITING_REWARD_INTEGRATION' AND c.region_code IN ("+placeholders(regions.size())+") ORDER BY i.created_at,i.id LIMIT ? OFFSET ?",args.toArray()),page,size);
    }
    private String placeholders(int count){return String.join(",",java.util.Collections.nCopies(count,"?"));}
}
