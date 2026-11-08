package tech.kayys.farabi.engine;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import tech.kayys.farabi.engine.SemanticQueryContracts.*;
import static org.junit.jupiter.api.Assertions.*;

class SemanticQueryValidatorTest {
    @Test void validatesAuthorizedDimensionsMeasuresAndTypedPredicates() {
        var catalog = catalog();
        var validator = new SemanticQueryValidator(catalog);
        var query = new Query("sales",
                List.of(new FieldRef("region")),
                List.of(new Measure(new FieldRef("amount"), Aggregation.SUM, "revenue")),
                List.of(new Predicate(new FieldRef("amount"), Operator.GTE, FieldType.DECIMAL, new BigDecimal("10.00"))),
                500);
        assertSame(query, validator.validate("workspace", "alice", query));
    }

    @Test void authorizesDatasetBeforeResolvingFields() {
        var calls = new ArrayList<String>();
        Catalog catalog = new Catalog() {
            public void authorizeDataset(String w, String s, String d) { calls.add("authorize"); throw new SecurityException(); }
            public CatalogField requireField(String w, String s, String d, FieldRef f) { calls.add("field"); return null; }
        };
        var query = new Query("secret", List.of(new FieldRef("salary")), List.of(), List.of(), 10);
        assertThrows(SecurityException.class, () -> new SemanticQueryValidator(catalog).validate("ws", "bob", query));
        assertEquals(List.of("authorize"), calls);
    }

    @Test void rejectsUnknownFieldsAndInvalidAggregationTypes() {
        var validator = new SemanticQueryValidator(catalog());
        var unknown = new Query("sales", List.of(new FieldRef("unknown")), List.of(), List.of(), 10);
        assertThrows(IllegalArgumentException.class, () -> validator.validate("ws", "alice", unknown));
        var invalidSum = new Query("sales", List.of(), List.of(
                new Measure(new FieldRef("region"), Aggregation.SUM, "total")), List.of(), 10);
        assertThrows(IllegalArgumentException.class, () -> validator.validate("ws", "alice", invalidSum));
    }

    @Test void rejectsPredicateTypeMismatchAtCatalogBoundary() {
        var query = new Query("sales", List.of(new FieldRef("region")), List.of(), List.of(
                new Predicate(new FieldRef("region"), Operator.EQ, FieldType.DECIMAL, BigDecimal.ONE)), 10);
        assertThrows(IllegalArgumentException.class,
                () -> new SemanticQueryValidator(catalog()).validate("ws", "alice", query));
    }

    @Test void rejectsMismatchedLiteralTypesAndUnsafeLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> new Predicate(new FieldRef("region"), Operator.EQ, FieldType.TEXT, 42));
        assertThrows(IllegalArgumentException.class,
                () -> new Query("sales", List.of(), List.of(new Measure(new FieldRef("amount"), Aggregation.SUM, "sum")),
                        List.of(), 10001));
    }

    private static Catalog catalog() {
        Map<String, CatalogField> fields = Map.of(
                "region", new CatalogField(new FieldRef("region"), FieldType.TEXT, true, false),
                "amount", new CatalogField(new FieldRef("amount"), FieldType.DECIMAL, false, true));
        return new Catalog() {
            public void authorizeDataset(String w, String s, String d) { assertEquals("sales", d); }
            public CatalogField requireField(String w, String s, String d, FieldRef f) {
                CatalogField result = fields.get(f.name());
                if (result == null) throw new IllegalArgumentException("unknown field: " + f.name());
                return result;
            }
        };
    }
}
