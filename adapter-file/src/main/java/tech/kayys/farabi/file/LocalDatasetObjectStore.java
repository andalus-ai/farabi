package tech.kayys.farabi.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import tech.kayys.farabi.engine.DatasetStorageLifecyclePorts.ObjectStore;

/** Workspace-fenced object deletion for the local development storage provider. */
public final class LocalDatasetObjectStore implements ObjectStore {
    private final Path root;
    public LocalDatasetObjectStore(Path root){this.root=Objects.requireNonNull(root).toAbsolutePath().normalize();}
    @Override public void delete(String ref,String workspace){
        String[] parts=ref.split(":",3);
        if(parts.length!=3||!"local-file".equals(parts[0])||!workspace.equals(parts[1]))
            throw new SecurityException("dataset object does not belong to workspace");
        UUID id=UUID.fromString(parts[2]);
        Path dir=root.resolve(hash(workspace)).normalize();
        Path target=dir.resolve(id+".csv");
        for(String suffix:new String[]{".csv",".tsv",".json",".jsonl"}){
            Path candidate=dir.resolve(id+suffix).normalize();
            if(!candidate.startsWith(dir))throw new SecurityException("invalid object path");
            try { if(Files.deleteIfExists(candidate)) return; }
            catch(IOException e){throw new IllegalStateException("unable to delete local dataset object",e);}
        }
    }
    @Override public boolean exists(String ref,String workspace){
        String[] parts=ref.split(":",3);
        if(parts.length!=3||!"local-file".equals(parts[0])||!workspace.equals(parts[1]))
            throw new SecurityException("dataset object does not belong to workspace");
        UUID id=UUID.fromString(parts[2]);
        Path dir=root.resolve(hash(workspace)).normalize();
        for(String suffix:new String[]{".csv",".tsv",".json",".jsonl"}){
            Path candidate=dir.resolve(id+suffix).normalize();
            if(!candidate.startsWith(dir))throw new SecurityException("invalid object path");
            if(Files.exists(candidate,java.nio.file.LinkOption.NOFOLLOW_LINKS))return true;
        }
        return false;
    }
    private static String hash(String s){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
}
