package tech.kayys.farabi.file;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.kayys.farabi.engine.DatasetContracts.Format;
import tech.kayys.farabi.engine.DatasetContracts.LogicalType;
import tech.kayys.farabi.engine.DatasetImportPorts.ImportRequest;

class LocalFileDatasetAdapterTest {
    @TempDir java.nio.file.Path temp;

    @Test
    void stagesAndProfilesCsvWithInferredTypesAndNullCounts() {
        String csv = "id,amount,active\n1,12.5,true\n2,,false\n";
        var adapter = adapter(csv);
        var request = request(Format.CSV, csv);
        var staged = adapter.stage(request);
        var profile = adapter.profile(UUID.randomUUID(), "workspace-a", staged);

        assertEquals(2, profile.rowCount());
        assertEquals(LogicalType.INTEGER, profile.columns().get(0).logicalType());
        assertEquals(LogicalType.DECIMAL, profile.columns().get(1).logicalType());
        assertEquals(1, profile.columns().get(1).nullCount());
        assertEquals(LogicalType.BOOLEAN, profile.columns().get(2).logicalType());
    }

    @Test
    void profilesJsonObjectArrays() {
        String json = "[{\"id\":1,\"label\":\"a\"},{\"id\":2,\"label\":null}]";
        var adapter = adapter(json);
        var profile = adapter.profile(UUID.randomUUID(), "workspace-a", adapter.stage(request(Format.JSON, json)));
        assertEquals(2, profile.rowCount());
        assertEquals(LogicalType.INTEGER, profile.columns().get(0).logicalType());
        assertEquals(1, profile.columns().get(1).nullCount());
    }

    @Test
    void rejectsOversizedAndCrossWorkspaceAccess() {
        String csv = "id\n1\n";
        var adapter = new LocalFileDatasetAdapter(temp, r -> new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                new LocalFileDatasetAdapter.Limits(1024, 10, 5, 10), Clock.systemUTC());
        var staged = adapter.stage(request(Format.CSV, csv));
        assertThrows(SecurityException.class, () -> adapter.profile(UUID.randomUUID(), "workspace-b", staged));
        assertThrows(IllegalArgumentException.class, () -> new LocalFileDatasetAdapter(temp,
                r -> new ByteArrayInputStream(new byte[2048]),
                new LocalFileDatasetAdapter.Limits(1024, 10, 5, 10), Clock.systemUTC())
                .stage(new ImportRequest("workspace-a", "user", "x", "upload", Format.CSV, "x.csv", 2048, "text/csv")));
    }

    private LocalFileDatasetAdapter adapter(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return new LocalFileDatasetAdapter(temp, r -> new ByteArrayInputStream(bytes),
                new LocalFileDatasetAdapter.Limits(1024 * 1024, 100, 20, 100), Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC));
    }

    private ImportRequest request(Format format, String value) {
        return new ImportRequest("workspace-a", "user", "dataset", "upload", format,
                "input", value.getBytes(StandardCharsets.UTF_8).length, "application/octet-stream");
    }
}
