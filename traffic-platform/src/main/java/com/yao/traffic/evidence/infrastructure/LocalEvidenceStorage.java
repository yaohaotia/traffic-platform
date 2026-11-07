package com.yao.traffic.evidence.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Explicit local/test adapter; it is intentionally unavailable in the prod profile. */
@Component
@ConditionalOnProperty(prefix="traffic.storage", name="provider", havingValue="local", matchIfMissing=true)
public class LocalEvidenceStorage extends FileEvidenceStorage {
    public LocalEvidenceStorage(Environment environment, @Value("${traffic.storage.local-root:./data/evidence}") String directory) {
        super(developmentDirectory(environment,directory));
    }
    private static String developmentDirectory(Environment environment,String directory){
        if (environment.acceptsProfiles(Profiles.of("prod"))
                || !environment.acceptsProfiles(Profiles.of("local", "test", "integration")))
            throw new IllegalStateException("Local evidence storage is available only for explicit development/test profiles");
        return directory;
    }
}
