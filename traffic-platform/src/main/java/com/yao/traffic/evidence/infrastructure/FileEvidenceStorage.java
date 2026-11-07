package com.yao.traffic.evidence.infrastructure;

import com.yao.traffic.evidence.domain.EvidenceStorage;
import com.yao.traffic.shared.web.DependencyUnavailableException;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

/** Immutable server-generated object keys; subclasses choose an explicit storage deployment. */
public abstract class FileEvidenceStorage implements EvidenceStorage {
    private final Path root;
    protected FileEvidenceStorage(String directory){
        try{var target=Path.of(directory).toAbsolutePath().normalize();Files.createDirectories(target);root=target.toRealPath();}
        catch(IOException failure){throw new DependencyUnavailableException("Evidence storage",failure);}
    }
    @Override public String write(byte[] content,String extension){
        if(!extension.equals("png")&&!extension.equals("jpg"))throw new IllegalArgumentException("Unsupported extension");
        String key=UUID.randomUUID()+"."+extension;
        try{Files.write(path(key),content,StandardOpenOption.CREATE_NEW);return key;}
        catch(IOException failure){throw new DependencyUnavailableException("Evidence storage",failure);}
    }
    @Override public byte[] read(String key){
        try{return Files.readAllBytes(path(key));}catch(IOException failure){throw new DependencyUnavailableException("Evidence storage",failure);}
    }
    @Override public void delete(String key){try{Files.deleteIfExists(path(key));}catch(IOException failure){throw new DependencyUnavailableException("Evidence storage",failure);}}
    public boolean available(){return Files.isDirectory(root)&&Files.isReadable(root)&&Files.isWritable(root);}
    private Path path(String key){
        if(key==null||!key.matches("[0-9a-f-]{36}\\.(png|jpg)"))throw new IllegalArgumentException("Invalid storage object key");
        Path target=root.resolve(key).normalize();
        if(!target.getParent().equals(root)||Files.isSymbolicLink(target))throw new IllegalArgumentException("Invalid storage object");
        return target;
    }
}
