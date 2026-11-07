package com.yao.traffic.evidence.infrastructure;

import com.yao.traffic.evidence.application.EvidenceService;
import com.yao.traffic.evidence.domain.Evidence;
import com.yao.traffic.evidence.domain.EvidenceContent;
import com.yao.traffic.evidence.domain.EvidenceStorage;
import com.yao.traffic.shared.web.DependencyUnavailableException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Authorisation happens in owner/review application services before any object bytes are read. */
@Component
public class EvidenceContentReader {
    private final EvidenceStorage storage;
    public EvidenceContentReader(EvidenceStorage storage){this.storage=storage;}
    public EvidenceContent read(Evidence item){
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Evidence storage I/O must run outside a database transaction");
        if(item.sizeBytes()<1||item.sizeBytes()>5*1024*1024||(!"image/png".equals(item.mediaType())&&!"image/jpeg".equals(item.mediaType())))
            throw new DependencyUnavailableException("Evidence metadata",null);
        byte[] bytes=storage.read(item.objectKey());
        if(bytes==null||bytes.length!=item.sizeBytes()||!EvidenceService.sha256(bytes).equals(item.sha256()))
            throw new DependencyUnavailableException("Evidence integrity",null);
        String extension="image/png".equals(item.mediaType())?"png":"jpg";
        return new EvidenceContent(bytes,item.mediaType(),"evidence-"+item.id()+"."+extension);
    }
}
