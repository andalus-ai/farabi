package tech.kayys.farabi.file;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import tech.kayys.farabi.engine.DatasetContracts.Column;
import tech.kayys.farabi.engine.DatasetContracts.Format;
import tech.kayys.farabi.engine.DatasetContracts.LogicalType;
import tech.kayys.farabi.engine.DatasetContracts.Profile;
import tech.kayys.farabi.engine.DatasetImportPorts.ImportRequest;
import tech.kayys.farabi.engine.DatasetImportPorts.Profiler;
import tech.kayys.farabi.engine.DatasetImportPorts.UploadSourceIntake;
import tech.kayys.farabi.engine.DatasetImportPorts.StagedSource;
import tech.kayys.farabi.engine.DatasetStorageInventoryPorts.Inventory;
import tech.kayys.farabi.engine.DatasetStorageInventoryPorts.ObjectEntry;

/**
 * Bounded local-filesystem adapter for CSV, JSON object arrays and JSONL.
 * It is suitable for development/single-node deployments; production deployments should
 * replace it with an encrypted object-storage adapter implementing the same ports.
 */
public final class LocalFileDatasetAdapter implements UploadSourceIntake, Profiler, Inventory {
    @FunctionalInterface
    public interface ContentProvider {
        InputStream open(ImportRequest request) throws IOException;
    }

    public record Limits(long maxBytes, long maxRows, int maxColumns, int maxDistinctValues) {
        public Limits {
            if (maxBytes <= 0 || maxRows <= 0 || maxColumns <= 0 || maxDistinctValues <= 0) {
                throw new IllegalArgumentException("all import limits must be positive");
            }
        }
    }

    private final Path root;
    private final ContentProvider contentProvider;
    private final Limits limits;
    private final Clock clock;
    private final JsonFactory jsonFactory = new JsonFactory();

    public LocalFileDatasetAdapter(Path root, ContentProvider contentProvider, Limits limits, Clock clock) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.contentProvider = Objects.requireNonNull(contentProvider, "contentProvider");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public StagedSource stage(ImportRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            return stage(request, contentProvider.open(request));
        } catch (IOException e) {
            throw new IllegalStateException("unable to open dataset upload", e);
        }
    }

    @Override
    public StagedSource stage(ImportRequest request, InputStream content) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(content, "content");
        if (!supported(request.format())) throw new IllegalArgumentException("supported formats: CSV, TSV, JSON, JSONL");
        if (request.contentLength() > limits.maxBytes()) throw new IllegalArgumentException("upload exceeds configured byte limit");
        UUID id = UUID.randomUUID();
        Path directory = workspaceDirectory(request.workspaceId());
        String suffix = switch (request.format()) {
            case TSV -> ".tsv";
            case JSON -> ".json";
            case JSONL -> ".jsonl";
            default -> ".csv";
        };
        Path target = directory.resolve(id + suffix).normalize();
        if (!target.startsWith(directory)) throw new SecurityException("invalid staged path");
        try {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, id.toString(), ".part");
            long copied = 0;
            try (InputStream input = content;
                 OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    copied += read;
                    if (copied > limits.maxBytes()) throw new IllegalArgumentException("upload exceeds configured byte limit");
                    output.write(buffer, 0, read);
                }
            } catch (Exception e) {
                Files.deleteIfExists(temporary);
                throw e;
            }
            if (copied != request.contentLength()) {
                Files.deleteIfExists(temporary);
                throw new IllegalArgumentException("uploaded byte count does not match declared content length");
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target);
            }
            return new StagedSource(request.format(), "local-file:" + request.workspaceId() + ":" + id, copied);
        } catch (IOException e) {
            throw new IllegalStateException("unable to stage dataset upload", e);
        }
    }

    @Override
    public Profile profile(UUID datasetId, String workspaceId, StagedSource source) {
        Objects.requireNonNull(datasetId, "datasetId");
        Objects.requireNonNull(source, "source");
        Path file = resolve(source, workspaceId);
        try {
            ProfileAccumulator accumulator = new ProfileAccumulator();
            switch (source.format()) {
                case CSV, TSV -> profileDelimited(file, source.format() == Format.TSV ? '\t' : ',', accumulator);
                case JSON -> profileJson(file, accumulator, false);
                case JSONL -> profileJson(file, accumulator, true);
                default -> throw new IllegalArgumentException("unsupported profile format: " + source.format());
            }
            return accumulator.toProfile(datasetId, workspaceId, clock.instant(), "farabi-file-profiler/1");
        } catch (IOException e) {
            throw new IllegalStateException("unable to profile staged dataset", e);
        }
    }

    private void profileDelimited(Path file, char delimiter, ProfileAccumulator acc) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) throw new IllegalArgumentException("delimited file is empty");
            List<String> names = parseDelimited(header, delimiter);
            acc.setColumns(names);
            String line;
            while ((line = reader.readLine()) != null) acc.addRow(parseDelimited(line, delimiter));
        }
    }

    private List<String> parseDelimited(String line, char delimiter) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"'); i++;
                } else quoted = !quoted;
            } else if (ch == delimiter && !quoted) {
                fields.add(field.toString()); field.setLength(0);
            } else field.append(ch);
        }
        if (quoted) throw new IllegalArgumentException("unterminated quoted field in delimited record");
        fields.add(field.toString());
        return fields;
    }

    private void profileJson(Path file, ProfileAccumulator acc, boolean jsonLines) throws IOException {
        try (JsonParser parser = jsonFactory.createParser(file.toFile())) {
            if (jsonLines) {
                while (parser.nextToken() != null) {
                    if (parser.currentToken() != JsonToken.START_OBJECT) throw new IllegalArgumentException("each JSONL record must be an object");
                    acc.addObject(readObject(parser));
                }
            } else {
                JsonToken first = parser.nextToken();
                if (first == JsonToken.START_ARRAY) {
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        if (parser.currentToken() != JsonToken.START_OBJECT) throw new IllegalArgumentException("JSON array entries must be objects");
                        acc.addObject(readObject(parser));
                    }
                } else if (first == JsonToken.START_OBJECT) {
                    acc.addObject(readObject(parser));
                    if (parser.nextToken() != null) throw new IllegalArgumentException("JSON input must contain one object or an array of objects");
                } else throw new IllegalArgumentException("JSON input must be an object or an array of objects");
            }
        }
    }

    private Map<String, String> readObject(JsonParser parser) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) throw new IllegalArgumentException("invalid JSON object");
            String name = parser.currentName();
            JsonToken valueToken = parser.nextToken();
            if (valueToken == JsonToken.START_OBJECT || valueToken == JsonToken.START_ARRAY) {
                parser.skipChildren();
                values.put(name, "[complex]");
            } else if (valueToken == JsonToken.VALUE_NULL) values.put(name, null);
            else values.put(name, parser.getValueAsString());
        }
        return values;
    }

    /** Removes an ephemeral local staging copy after a delegated profile operation. */
    public void deleteStaged(StagedSource source, String workspaceId) {
        Path file = resolve(source, workspaceId);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new IllegalStateException("unable to remove temporary staged dataset", e);
        }
    }

    @Override
    public Optional<ObjectEntry> inspect(String locationRef, String workspaceId) {
        StagedSource source = sourceFromReference(locationRef, workspaceId);
        Path file = resolve(source, workspaceId);
        try {
            return Optional.of(new ObjectEntry(locationRef, Files.size(file),
                    Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant()));
        } catch (IOException e) {
            throw new IllegalStateException("unable to inspect local dataset object", e);
        }
    }

    @Override
    public List<ObjectEntry> list(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) throw new IllegalArgumentException("workspaceId required");
        Path directory = workspaceDirectory(workspaceId);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new SecurityException("workspace storage directory is not a safe directory");
        try (var paths = Files.list(directory)) {
            List<ObjectEntry> result = new ArrayList<>();
            for (Path path : paths.toList()) {
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                String name = path.getFileName().toString();
                Format format = formatFromExtension(name);
                if (format == null) continue;
                String id = name.substring(0, name.lastIndexOf('.'));
                try { UUID.fromString(id); } catch (IllegalArgumentException ignored) { continue; }
                result.add(new ObjectEntry("local-file:" + workspaceId + ":" + id,
                        Files.size(path), Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant()));
            }
            result.sort(Comparator.comparing(ObjectEntry::locationRef));
            return List.copyOf(result);
        } catch (IOException e) {
            throw new IllegalStateException("unable to list workspace dataset objects", e);
        }
    }

    private StagedSource sourceFromReference(String ref, String workspaceId) {
        if (ref == null || workspaceId == null || workspaceId.isBlank())
            throw new IllegalArgumentException("object reference and workspace required");
        String[] parts = ref.split(":", 3);
        if (parts.length != 3 || !"local-file".equals(parts[0]) || !workspaceId.equals(parts[1]))
            throw new SecurityException("object reference does not belong to workspace");
        UUID.fromString(parts[2]);
        Path directory = workspaceDirectory(workspaceId);
        if (Files.isSymbolicLink(root) || Files.isSymbolicLink(directory))
            throw new SecurityException("symbolic links are not permitted in dataset storage");
        try (var paths = Files.list(directory)) {
            // Existence is checked by resolve; this only validates the identifier and workspace scope.
        } catch (IOException | java.nio.file.NoSuchFileException ignored) {
            // An absent workspace directory means the referenced object is absent.
        }
        String fileName = parts[2] + ".";
        Format format = null;
        for (Format candidate : List.of(Format.CSV, Format.TSV, Format.JSON, Format.JSONL)) {
            if (Files.exists(directory.resolve(parts[2] + extension(candidate)), LinkOption.NOFOLLOW_LINKS)) {
                format = candidate; break;
            }
        }
        if (format == null) throw new java.nio.file.NoSuchFileException(ref);
        return new StagedSource(format, ref);
    }

    private static String extension(Format format) {
        return switch (format) { case TSV -> ".tsv"; case JSON -> ".json"; case JSONL -> ".jsonl"; default -> ".csv"; };
    }

    private static Format formatFromExtension(String name) {
        if (name.endsWith(".csv")) return Format.CSV;
        if (name.endsWith(".tsv")) return Format.TSV;
        if (name.endsWith(".json")) return Format.JSON;
        if (name.endsWith(".jsonl")) return Format.JSONL;
        return null;
    }

    private Path resolve(StagedSource source, String workspaceId) {
        String[] parts = source.locationRef().split(":", 3);
        if (parts.length != 3 || !"local-file".equals(parts[0]) || !workspaceId.equals(parts[1])) {
            throw new SecurityException("staged source does not belong to workspace");
        }
        UUID id;
        try { id = UUID.fromString(parts[2]); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("invalid staged source reference", e); }
        String suffix = switch (source.format()) {
            case TSV -> ".tsv";
            case JSON -> ".json";
            case JSONL -> ".jsonl";
            default -> ".csv";
        };
        Path directory = workspaceDirectory(workspaceId);
        Path path = directory.resolve(id + suffix).normalize();
        if (!path.startsWith(directory) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) throw new IllegalArgumentException("staged source not found");
        return path;
    }

    private Path workspaceDirectory(String workspaceId) {
        return root.resolve(sha256(workspaceId)).normalize();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : digest) result.append(String.format("%02x", b));
            return result.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static boolean supported(Format format) {
        return format == Format.CSV || format == Format.TSV || format == Format.JSON || format == Format.JSONL;
    }

    private final class ProfileAccumulator {
        private final Map<String, ColumnAccumulator> columns = new LinkedHashMap<>();
        private long rows;

        void setColumns(List<String> names) {
            if (names.isEmpty() || names.size() > limits.maxColumns()) throw new IllegalArgumentException("invalid column count");
            for (String name : names) {
                if (name == null || name.isBlank() || columns.putIfAbsent(name, new ColumnAccumulator()) != null) {
                    throw new IllegalArgumentException("column names must be non-empty and unique");
                }
            }
        }

        void addRow(List<String> values) {
            if (values.size() != columns.size()) throw new IllegalArgumentException("record column count differs from header");
            int i = 0;
            for (ColumnAccumulator column : columns.values()) column.add(values.get(i++));
            incrementRows();
        }

        void addObject(Map<String, String> values) {
            for (String key : values.keySet()) {
                if (!columns.containsKey(key)) {
                    if (columns.size() >= limits.maxColumns()) throw new IllegalArgumentException("column limit exceeded");
                    columns.put(key, new ColumnAccumulator());
                    for (long i = 0; i < rows; i++) columns.get(key).add(null);
                }
            }
            for (Map.Entry<String, ColumnAccumulator> entry : columns.entrySet()) entry.getValue().add(values.get(entry.getKey()));
            incrementRows();
        }

        private void incrementRows() {
            rows++;
            if (rows > limits.maxRows()) throw new IllegalArgumentException("row limit exceeded");
        }

        Profile toProfile(UUID id, String workspace, java.time.Instant at, String version) {
            List<Column> result = new ArrayList<>();
            columns.forEach((name, value) -> result.add(new Column(name, value.type(), value.nullable, value.nulls, value.distinct.size())));
            return new Profile(id, workspace, rows, result, at, version);
        }
    }

    private final class ColumnAccumulator {
        private final Set<String> distinct = new HashSet<>();
        private long nulls;
        private boolean nullable;
        private LogicalType type = LogicalType.UNKNOWN;

        void add(String value) {
            if (value == null || value.isEmpty()) {
                nulls++; nullable = true; return;
            }
            distinct.add(value);
            if (distinct.size() > limits.maxDistinctValues()) throw new IllegalArgumentException("distinct-value limit exceeded");
            type = merge(type, infer(value));
        }

        LogicalType type() { return type; }
    }

    private static LogicalType infer(String value) {
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) return LogicalType.BOOLEAN;
        try { Long.parseLong(value); return LogicalType.INTEGER; } catch (NumberFormatException ignored) {}
        try { Double.parseDouble(value); return LogicalType.DECIMAL; } catch (NumberFormatException ignored) {}
        try { java.time.LocalDate.parse(value); return LogicalType.DATE; } catch (java.time.format.DateTimeParseException ignored) {}
        try { java.time.Instant.parse(value); return LogicalType.TIMESTAMP; } catch (java.time.format.DateTimeParseException ignored) {}
        return LogicalType.STRING;
    }

    private static LogicalType merge(LogicalType left, LogicalType right) {
        if (left == LogicalType.UNKNOWN) return right;
        if (left == right) return left;
        if ((left == LogicalType.INTEGER && right == LogicalType.DECIMAL)
                || (left == LogicalType.DECIMAL && right == LogicalType.INTEGER)) return LogicalType.DECIMAL;
        return LogicalType.STRING;
    }
}
