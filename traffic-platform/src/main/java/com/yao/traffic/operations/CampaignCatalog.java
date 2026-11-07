package com.yao.traffic.operations;

import com.yao.traffic.campaign.application.CampaignService;
import com.yao.traffic.campaign.domain.Campaign;
import com.yao.traffic.shared.security.CurrentActor;
import com.yao.traffic.shared.web.BusinessException;
import com.yao.traffic.shared.web.DependencyUnavailableException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

/** Display-only cache. Submission, review and money services never use cached policy facts. */
@Service
@ConditionalOnProperty(prefix="traffic.operations",name="enabled",havingValue="true")
public class CampaignCatalog {
    private final JdbcTemplate jdbc;private final StringRedisTemplate redis;private final ObjectMapper json;
    private final String namespace;
    public CampaignCatalog(JdbcTemplate jdbc,StringRedisTemplate redis,ObjectMapper json,@Value("${traffic.messaging.namespace:traffic}")String namespace){this.jdbc=jdbc;this.redis=redis;this.json=json;this.namespace=namespace;}
    public Campaign get(long id){
        final Campaign campaign;
        try{
            String cached=redis.opsForValue().get(key(id));
            if(cached!=null)campaign=json.readValue(cached,Campaign.class);
            else{
                campaign=jdbc.query("SELECT * FROM campaign WHERE id=?",CampaignService.MAPPER,id).stream().findFirst().orElseThrow(()->new BusinessException(HttpStatus.NOT_FOUND,"CAMPAIGN_NOT_FOUND","征集任务不存在"));
                redis.opsForValue().set(key(id),json.writeValueAsString(campaign),Duration.ofSeconds(30));
            }
        }catch(BusinessException business){throw business;}
        catch(RuntimeException unavailable){throw new DependencyUnavailableException("Campaign catalog",unavailable);}
        var actor=CurrentActor.profile();
        if(!actor.permissions().contains("campaign:manage")&&(!actor.regionCodes().contains(campaign.regionCode())||!"PUBLISHED".equals(campaign.state())))
            throw new BusinessException(HttpStatus.NOT_FOUND,"CAMPAIGN_NOT_FOUND","征集任务不存在");
        return campaign;
    }
    public void invalidateAfterCommit(long id){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Invalidation requires the campaign transaction");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){
            try{redis.delete(key(id));}catch(RuntimeException unavailable){org.slf4j.LoggerFactory.getLogger(CampaignCatalog.class).warn("Campaign cache invalidation deferred to its bounded 30-second TTL");}
        }});
    }
    private String key(long id){return "traffic:"+namespace+":catalog:campaign:"+id;}
}
