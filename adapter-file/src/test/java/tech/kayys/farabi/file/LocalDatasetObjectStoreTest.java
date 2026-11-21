package tech.kayys.farabi.file;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalDatasetObjectStoreTest {
    @TempDir Path root;

    @Test void verifiesPresenceAndAbsenceWithinWorkspaceBoundary() throws Exception {
        String workspace="workspace-a";
        UUID id=UUID.randomUUID();
        Path directory=root.resolve(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(workspace.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        Files.createDirectories(directory);
        Path object=directory.resolve(id+".jsonl");
        Files.writeString(object,"{}");
        var store=new LocalDatasetObjectStore(root);
        String ref="local-file:"+workspace+":"+id;

        assertTrue(store.exists(ref,workspace));
        store.delete(ref,workspace);
        assertFalse(store.exists(ref,workspace));
        assertDoesNotThrow(()->store.delete(ref,workspace));
        assertThrows(SecurityException.class,()->store.exists(ref,"workspace-b"));
    }
}
