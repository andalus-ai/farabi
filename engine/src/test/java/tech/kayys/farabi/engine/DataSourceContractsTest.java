package tech.kayys.farabi.engine;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DataSourceContractsTest {
    @Test void descriptorRequiresStableProviderIdentity() {
        assertDoesNotThrow(() -> new DataSourceContracts.Descriptor(
            "warehouse", "postgresql", "Warehouse", "1.0"));
        assertThrows(IllegalArgumentException.class, () -> new DataSourceContracts.Descriptor(
            " ", "postgresql", "Warehouse", "1.0"));
    }

    @Test void capabilitiesAreImmutableAndBounded() {
        var features = EnumSet.of(DataSourceContracts.Feature.PARAMETERIZED_QUERIES);
        var capabilities = new DataSourceContracts.Capabilities(features, 1000, 100);
        features.clear();
        assertTrue(capabilities.supports(DataSourceContracts.Feature.PARAMETERIZED_QUERIES));
        assertThrows(UnsupportedOperationException.class,
            () -> capabilities.features().clear());
        assertThrows(IllegalArgumentException.class,
            () -> new DataSourceContracts.Capabilities(Set.of(), 10, 11));
    }

    @Test void relationMetadataRequiresARealRelationKindAndCopiesColumns() {
        var columns = new java.util.ArrayList<DataSourceContracts.ColumnMetadata>();
        columns.add(new DataSourceContracts.ColumnMetadata(
            "revenue", DataSourceContracts.LogicalType.DECIMAL, false));
        var relation = new DataSourceContracts.RelationMetadata(
            "sales.orders", "sales", "public", "orders",
            DataSourceContracts.EntryKind.TABLE, columns);
        columns.clear();
        assertEquals(1, relation.columns().size());
        assertThrows(IllegalArgumentException.class, () ->
            new DataSourceContracts.RelationMetadata(
                "sales", null, null, "sales",
                DataSourceContracts.EntryKind.SCHEMA, List.of()));
    }

    @Test void jdbcMetadataConstructorsNormalizeIntoOneContract() {
        var table = new DataSourceContracts.CatalogEntry(
                "analytics", "public", "orders", "TABLE");
        assertEquals("orders", table.name());
        assertEquals(DataSourceContracts.EntryKind.TABLE, table.kind());
        assertEquals("analytics", table.catalog());
        assertEquals("public", table.schema());

        var column = new DataSourceContracts.ColumnMetadata(
                "amount", "DECIMAL(12,2)", DataSourceContracts.LogicalType.DECIMAL, false, 2);
        assertEquals(DataSourceContracts.LogicalType.DECIMAL, column.logicalType());
        assertEquals("DECIMAL(12,2)", column.nativeType());
        assertEquals(2, column.ordinalPosition());
    }
}
