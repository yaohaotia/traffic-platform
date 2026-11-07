package com.yao.traffic.evidence.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.nio.file.Path;

/** All replicas must mount the SAME persistent volume. This is not a cloud object-store SDK. */
@Component
@ConditionalOnProperty(prefix="traffic.storage",name="provider",havingValue="shared-volume")
public class SharedVolumeEvidenceStorage extends FileEvidenceStorage {
    public SharedVolumeEvidenceStorage(@Value("${traffic.storage.shared-root}")String directory){super(checked(directory));}
    private static String checked(String directory){
        if(directory==null||directory.isBlank()||!Path.of(directory).isAbsolute())throw new IllegalArgumentException("Explicit absolute shared storage mount required");
        return directory;
    }
}
