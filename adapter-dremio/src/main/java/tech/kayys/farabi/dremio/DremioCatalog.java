package tech.kayys.farabi.dremio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import tech.kayys.farabi.engine.DataSourceContracts.Catalog;
import tech.kayys.farabi.engine.DataSourceContracts.CatalogEntry;
import tech.kayys.farabi.engine.DataSourceContracts.ColumnMetadata;
import tech.kayys.farabi.engine.DataSourceContracts.EntryKind;
import tech.kayys.farabi.engine.DataSourceContracts.LogicalType;
import tech.kayys.farabi.engine.DataSourceContracts.RelationMetadata;

/**
 * Dremio v3 catalog REST adapter.
 *
 * <p>Catalog entity IDs are kept opaque in Farabi. The adapter uses the
 * Dremio catalog collection for top-level sources/spaces and fetches each
 * entity by ID to enumerate its children and describe dataset fields.</p>
 */
public final class DremioCatalog implements Catalog {
    private static final String CATALOG_PATH = "/api/v3/catalog";

    @FunctionalInterface
    public interface Transport {
        Response get(URI uri, String bearerToken);
    }

    public record Response(int statusCode, String body) {}

    private final ObjectMapper json;
    private final URI base;
    private final Transport transport;
    private final String token;

    public DremioCatalog(HttpClient http, ObjectMapper json, URI base, String token) {
        this(json, base, (uri, bearer) -> send(http, uri, bearer), token);
    }

    public DremioCatalog(ObjectMapper json, URI base, Transport transport, String token) {
        this.json = Objects.requireNonNull(json, "json");
        this.transport = Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(base, "base");
        if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null
                || base.getRawQuery() != null || base.getRawFragment() != null) {
            throw new IllegalArgumentException("Dremio catalog endpoint must be an HTTPS base URL");
        }
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Dremio token required");
        this.base = base;
        this.token = token;
    }

    @Override
    public List<CatalogEntry> catalogs() {
        JsonNode response = call(CATALOG_PATH);
        JsonNode items = collection(response);
        List<CatalogEntry> result = new ArrayList<>();
        for (JsonNode item : items) {
            EntryKind kind = catalogKind(item.path("type").asText(""));
            if (kind == null) continue;
            result.add(entry(item, kind));
        }
        return List.copyOf(result);
    }

    @Override
    public List<CatalogEntry> schemas(String catalog) {
        JsonNode entity = entity(catalog);
        requireKind(entity, EntryKind.CATALOG, "catalog");
        List<CatalogEntry> result = new ArrayList<>();
        for (JsonNode child : children(entity)) {
            if (isFolder(child)) result.add(entry(child, EntryKind.SCHEMA));
        }
        return List.copyOf(result);
    }

    @Override
    public List<CatalogEntry> relations(String catalog, String schema) {
        JsonNode parent = schema == null || schema.isBlank() ? entity(catalog) : entity(schema);
        if (schema != null && !schema.isBlank()) {
            requireKind(parent, EntryKind.SCHEMA, "schema");
        } else {
            requireKind(parent, EntryKind.CATALOG, "catalog");
        }
        List<CatalogEntry> result = new ArrayList<>();
        for (JsonNode child : children(parent)) {
            EntryKind kind = relationKind(child.path("type").asText(""));
            if (kind != null) result.add(entry(child, kind));
        }
        return List.copyOf(result);
    }

    @Override
    public RelationMetadata describe(String relationId) {
        JsonNode dataset = entity(relationId);
        EntryKind kind = relationKind(dataset.path("type").asText(""));
        if (kind == null) throw new IllegalArgumentException("Dremio catalog entity is not a table or view");
        List<String> path = strings(dataset.path("path"));
        if (path.isEmpty()) throw new IllegalStateException("Dremio dataset response is missing its path");
        List<ColumnMetadata> columns = new ArrayList<>();
        JsonNode fields = dataset.path("fields");
        if (fields.isArray()) {
            for (JsonNode field : fields) {
                String name = field.path("name").asText("");
                if (name.isBlank()) continue;
                JsonNode type = field.path("type");
                String nativeType = type.isTextual() ? type.asText() : type.path("name").asText("");
                boolean nullable = field.path("nullable").asBoolean(true);
                columns.add(new ColumnMetadata(name, logicalType(nativeType), nullable));
            }
        }
        return new RelationMetadata(
                requiredText(dataset, "id"),
                path.size() > 1 ? path.get(0) : null,
                path.size() > 2 ? String.join(".", path.subList(1, path.size() - 1)) : null,
                path.get(path.size() - 1),
                kind,
                columns);
    }

    private JsonNode entity(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Dremio catalog entity ID required");
        return call(CATALOG_PATH + "/" + segment(id));
    }

    private JsonNode call(String path) {
        Response response = transport.get(base.resolve(path), token);
        if (response.statusCode() == 404) {
            throw new IllegalArgumentException("Dremio catalog entity was not found");
        }
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new SecurityException("Dremio catalog access was denied (HTTP " + response.statusCode() + ")");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Dremio catalog API HTTP " + response.statusCode());
        }
        if (response.body() == null || response.body().isBlank()) {
            throw new IllegalStateException("Dremio catalog API returned an empty response");
        }
        try {
            return json.readTree(response.body());
        } catch (IOException failure) {
            throw new IllegalStateException("Dremio catalog API returned malformed JSON", failure);
        }
    }

    private static Response send(HttpClient http, URI uri, String token) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = Objects.requireNonNull(http, "http")
                    .send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Dremio catalog request interrupted", interrupted);
        } catch (IOException failure) {
            throw new IllegalStateException("Dremio catalog transport failure", failure);
        }
    }

    private static JsonNode collection(JsonNode response) {
        if (response.isArray()) return response;
        for (String key : List.of("data", "items", "children", "contents")) {
            JsonNode value = response.path(key);
            if (value.isArray()) return value;
        }
        throw new IllegalStateException("Dremio catalog response does not contain an entity collection");
    }

    private static List<JsonNode> children(JsonNode entity) {
        for (String key : List.of("children", "contents", "items")) {
            JsonNode value = entity.path(key);
            if (value.isArray()) {
                List<JsonNode> result = new ArrayList<>();
                value.forEach(result::add);
                return result;
            }
        }
        return List.of();
    }

    private static CatalogEntry entry(JsonNode entity, EntryKind kind) {
        String id = requiredText(entity, "id");
        JsonNode pathNode = entity.path("path");
        String name = entity.path("name").asText("");
        if (name.isBlank() && pathNode.isArray() && !pathNode.isEmpty()) {
            name = pathNode.get(pathNode.size() - 1).asText("");
        }
        if (name.isBlank()) throw new IllegalStateException("Dremio catalog entity is missing its name");
        return new CatalogEntry(id, name, kind);
    }

    private static boolean isFolder(JsonNode entity) {
        String type = entity.path("type").asText("").toUpperCase(Locale.ROOT);
        return "FOLDER".equals(type);
    }

    private static EntryKind catalogKind(String type) {
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "SOURCE", "SPACE", "HOME" -> EntryKind.CATALOG;
            default -> null;
        };
    }

    private static EntryKind relationKind(String type) {
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "PHYSICAL_DATASET" -> EntryKind.TABLE;
            case "VIRTUAL_DATASET" -> EntryKind.VIEW;
            default -> null;
        };
    }

    private static void requireKind(JsonNode entity, EntryKind expected, String label) {
        EntryKind actual = catalogKind(entity.path("type").asText(""));
        if (expected == EntryKind.SCHEMA && isFolder(entity)) return;
        if (actual != expected) throw new IllegalArgumentException("Dremio entity is not a " + label);
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) throw new IllegalStateException("Dremio catalog response is missing " + field);
        return value;
    }

    private static List<String> strings(JsonNode array) {
        List<String> result = new ArrayList<>();
        if (array.isArray()) for (JsonNode value : array) {
            if (value.isTextual() && !value.asText().isBlank()) result.add(value.asText());
        }
        return result;
    }

    private static String segment(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static LogicalType logicalType(String nativeType) {
        String type = nativeType == null ? "" : nativeType.toUpperCase(Locale.ROOT);
        if (type.contains("JSON") || type.contains("MAP") || type.contains("STRUCT")) return LogicalType.JSON;
        if (type.contains("BOOL")) return LogicalType.BOOLEAN;
        if (type.contains("TINYINT") || type.contains("SMALLINT") || type.contains("INT") || type.contains("LONG")) return LogicalType.INTEGER;
        if (type.contains("DECIMAL") || type.contains("NUMERIC") || type.contains("FLOAT") || type.contains("DOUBLE")) return LogicalType.DECIMAL;
        if (type.contains("DATE") && !type.contains("TIME")) return LogicalType.DATE;
        if (type.contains("TIME")) return LogicalType.TIMESTAMP;
        if (type.contains("BINARY") || type.contains("VARBINARY")) return LogicalType.BINARY;
        if (type.contains("CHAR") || type.contains("STRING") || type.contains("VARCHAR")) return LogicalType.STRING;
        return LogicalType.UNKNOWN;
    }
}
