package com.yao.traffic.operations;

import com.yao.traffic.campaign.domain.Campaign;
import com.yao.traffic.shared.web.DependencyUnavailableException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix="traffic.operations",name="enabled",havingValue="true")
public class OperationsController {
    private static final Map<String,String> COUNTS=Map.of(
            "outboxPending","SELECT COUNT(*) FROM outbox_event WHERE status IN ('PENDING','PUBLISHING')",
            "outboxDead","SELECT COUNT(*) FROM outbox_event WHERE status='DEAD'",
            "detectionDead","SELECT COUNT(*) FROM detection_task WHERE status='DEAD'",
            "manualReviews","SELECT COUNT(*) FROM review_task WHERE status='MANUAL_REQUIRED'",
            "manualCorrections","SELECT COUNT(*) FROM reward_correction WHERE state='MANUAL_REQUIRED'",
            "reconciliationDrifts","SELECT COUNT(*) FROM ledger_drift WHERE active=TRUE");
    private final JdbcTemplate jdbc;private final CampaignCatalog catalog;
    public OperationsController(JdbcTemplate jdbc,CampaignCatalog catalog,MeterRegistry meters){
        this.jdbc=jdbc;this.catalog=catalog;
        COUNTS.forEach((name,sql)->Gauge.builder("traffic.backlog",()->{try{return jdbc.queryForObject(sql,Double.class);}catch(RuntimeException unavailable){return Double.NaN;}}).tag("kind",name).register(meters));
    }
    @GetMapping("/campaigns/{id}") public Campaign campaign(@PathVariable long id){return catalog.get(id);}
    @GetMapping("/operations/summary") @PreAuthorize("hasAuthority('operations:read')")
    public Map<String,Object> summary(){
        try{var result=new LinkedHashMap<String,Object>();COUNTS.forEach((name,sql)->result.put(name,jdbc.queryForObject(sql,Long.class)));
            result.put("jobs",jdbc.queryForList("SELECT job_name,last_success,last_failure,last_result FROM maintenance_checkpoint ORDER BY job_name"));return result;
        }catch(RuntimeException unavailable){throw new DependencyUnavailableException("Operations persistence",unavailable);}
    }
}
