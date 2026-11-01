package tech.kayys.farabi.s3;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import tech.kayys.farabi.engine.DatasetContracts.Format;
import tech.kayys.farabi.engine.DatasetImportPorts.ImportRequest;
import tech.kayys.farabi.file.LocalFileDatasetAdapter;

class S3CompatibleDatasetAdapterTest {
    @TempDir Path temp;

    @Test
    void stagesEncryptedObjectUnderOpaqueWorkspacePrefix() {
        S3Client client = mock(S3Client.class);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("etag").build());
        var adapter = new S3CompatibleDatasetAdapter(client, "farabi-data", temp, 1024,
                new LocalFileDatasetAdapter.Limits(1024, 100, 20, 100), Clock.systemUTC());
        byte[] bytes = "id\n1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var request = new ImportRequest("workspace-secret", "subject-a", "dataset-a", "source-a",
                Format.CSV, "customer.csv", bytes.length, "text/csv");

        var staged = adapter.stage(request, new ByteArrayInputStream(bytes));

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(captor.capture(), any(RequestBody.class));
        PutObjectRequest put = captor.getValue();
        assertEquals(ServerSideEncryption.AES256, put.serverSideEncryption());
        assertEquals((long) bytes.length, put.contentLength());
        assertEquals("text/csv", put.contentType());
        assertFalse(put.key().contains("workspace-secret"));
        assertEquals(staged.locationRef(), "s3://farabi-data/" + put.key());
        assertEquals(64, put.metadata().get("farabi-workspace").length());
        assertEquals("CSV", put.metadata().get("farabi-format"));
    }

    @Test
    void rejectsObjectReferenceFromAnotherWorkspaceBeforeReadingStorage() {
        S3Client client = mock(S3Client.class);
        var adapter = new S3CompatibleDatasetAdapter(client, "farabi-data", temp, 1024,
                new LocalFileDatasetAdapter.Limits(1024, 100, 20, 100), Clock.systemUTC());
        var source = new tech.kayys.farabi.engine.DatasetImportPorts.StagedSource(
                Format.CSV, "s3://farabi-data/datasets/" + "a".repeat(64) + "/object.csv");
        assertThrows(SecurityException.class,
                () -> adapter.profile(UUID.randomUUID(), "workspace-b", source));
    }
}
