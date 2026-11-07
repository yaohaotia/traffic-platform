package com.yao.traffic.operations;

import com.yao.traffic.evidence.domain.EvidenceStorage;
import com.yao.traffic.evidence.infrastructure.FileEvidenceStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("evidenceStorage")
@ConditionalOnProperty(prefix="traffic.operations",name="enabled",havingValue="true")
public class StorageHealth implements HealthIndicator {
    private final EvidenceStorage storage;
    public StorageHealth(EvidenceStorage storage){this.storage=storage;}
    @Override public Health health(){return storage instanceof FileEvidenceStorage file&&file.available()?Health.up().build():Health.down().build();}
}
