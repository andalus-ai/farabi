package tech.kayys.farabi.s3;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import tech.kayys.farabi.engine.DatasetContracts.Format;
import tech.kayys.farabi.engine.DatasetContracts.Profile;
import tech.kayys.farabi.engine.DatasetImportPorts.ImportRequest;
import tech.kayys.farabi.engine.DatasetImportPorts.Profiler;
import tech.kayys.farabi.engine.DatasetImportPorts.StagedSource;
import tech.kayys.farabi.engine.DatasetImportPorts.UploadSourceIntake;
import tech.kayys.farabi.file.LocalFileDatasetAdapter;

/**
 * S3-compatible durable object storage intake and profiler.
 * Uses opaque workspace-hashed keys and server-side encryption; credentials are supplied
 * by the AWS SDK credential provider chain, never stored in Farabi configuration.
 */
public final class S3CompatibleDatasetAdapter implements UploadSourceIntake, Profiler {
    private final S3Client s3;
    private final String bucket;
    private final Path temporaryRoot;
    private final long maxBytes;
    private final LocalFileDatasetAdapter.Limits profileLimits;
    private final Clock clock;

    public S3CompatibleDatasetAdapter(S3Client s3, String bucket, Path temporaryRoot,
            long maxBytes, LocalFileDatasetAdapter.Limits profileLimits, Clock clock) {
        this.s3 = Objects.requireNonNull(s3, "s3");
        if (bucket == null || bucket.isBlank()) throw new IllegalArgumentException("bucket required");
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");
        this.bucket = bucket;
        this.temporaryRoot = Objects.requireNonNull(temporaryRoot, "temporaryRoot").toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
        this.profileLimits = Objects.requireNonNull(profileLimits, "profileLimits");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public StagedSource stage(ImportRequest request) {
        throw new IllegalStateException("an upload stream is required");
    }

    @Override
    public StagedSource stage(ImportRequest request, InputStream content) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(content, "content");
        if (!supported(request.format())) throw new IllegalArgumentException("supported formats: CSV, TSV, JSON, JSONL");
        if (request.contentLength() > maxBytes) throw new IllegalArgumentException("upload exceeds configured byte limit");
        String workspaceKey = sha256(request.workspaceId());
        String key = "datasets/" + workspaceKey + "/" + UUID.randomUUID() + extension(request.format());
        try (InputStream upload = content) {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(bucket).key(key)
                            .contentLength(request.contentLength())
                            .contentType(request.contentType())
                            .serverSideEncryption(ServerSideEncryption.AES256)
                            .checksumAlgorithm(ChecksumAlgorithm.SHA256)
                            .metadata(Map.of("farabi-workspace", workspaceKey,
                                    "farabi-format", request.format().name()))
                            .build(),
                    RequestBody.fromInputStream(upload, request.contentLength()));
            return new StagedSource(request.format(), "s3://" + bucket + "/" + key);
        } catch (RuntimeException e) {
            throw new IllegalStateException("unable to stage dataset in object storage", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("unable to close dataset upload stream", e);
        }
    }

    @Override
    public Profile profile(UUID datasetId, String workspaceId, StagedSource source) {
        ObjectRef object = resolve(source, workspaceId);
        var head = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(object.key()).build());
        if (!object.workspaceHash().equals(head.metadata().get("farabi-workspace"))) {
            throw new SecurityException("object metadata does not match workspace scope");
        }
        if (!source.format().name().equals(head.metadata().get("farabi-format"))) {
            throw new IllegalArgumentException("stored object format does not match dataset metadata");
        }
        long length = head.contentLength();
        if (length > maxBytes) throw new IllegalArgumentException("stored object exceeds configured byte limit");

        Path operationRoot = temporaryRoot.resolve(UUID.randomUUID().toString()).normalize();
        LocalFileDatasetAdapter local = new LocalFileDatasetAdapter(operationRoot,
                request -> { throw new IllegalStateException("temporary profiler requires a stream"); },
                profileLimits, clock);
        StagedSource temporary = null;
        try (InputStream download = s3.getObject(GetObjectRequest.builder()
                .bucket(bucket).key(object.key()).build())) {
            ImportRequest internal = new ImportRequest(workspaceId, "farabi-profiler", "temporary-profile",
                    "object-storage", source.format(), "staged" + extension(source.format()),
                    length, head.contentType() == null ? "application/octet-stream" : head.contentType());
            temporary = local.stage(internal, download);
            return local.profile(datasetId, workspaceId, temporary);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("unable to close object download stream", e);
        } finally {
            if (temporary != null) {
                try { local.deleteStaged(temporary, workspaceId); }
                catch (RuntimeException ignored) { /* retained temp files are removed by deployment cleanup */ }
            }
            try {
                Files.deleteIfExists(operationRoot.resolve(sha256(workspaceId)));
                Files.deleteIfExists(operationRoot);
            }
            catch (java.io.IOException ignored) { /* cleanup is best effort */ }
        }
    }

    /** Deletes a staged object after an authorized retention/cleanup decision. */
    public void delete(StagedSource source, String workspaceId) {
        ObjectRef object = resolve(source, workspaceId);
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(object.key()).build());
    }

    private ObjectRef resolve(StagedSource source, String workspaceId) {
        Objects.requireNonNull(source, "source");
        String prefix = "s3://" + bucket + "/datasets/" + sha256(workspaceId) + "/";
        String ref = source.locationRef();
        if (!ref.startsWith(prefix) || ref.length() <= prefix.length()) {
            throw new SecurityException("staged object does not belong to workspace");
        }
        String key = ref.substring(("s3://" + bucket + "/").length());
        if (key.contains("..") || key.contains("//")) throw new IllegalArgumentException("invalid object reference");
        return new ObjectRef(key, sha256(workspaceId));
    }

    private static boolean supported(Format f) {
        return f == Format.CSV || f == Format.TSV || f == Format.JSON || f == Format.JSONL;
    }
    private static String extension(Format f) {
        return switch (f) {
            case TSV -> ".tsv";
            case JSON -> ".json";
            case JSONL -> ".jsonl";
            default -> ".csv";
        };
    }
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
    private record ObjectRef(String key, String workspaceHash) {}
}
