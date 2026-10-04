package com.strata.catalog;

import static com.strata.testutil.Assert.*;

import java.util.List;

public class TableInfoTest {

    public void testSerializeDeserializeRoundTrip() {
        TableInfo original = new TableInfo("users", 7, List.of(
            new ColumnDefinition("id", DataType.INTEGER),
            new ColumnDefinition("name", DataType.VARCHAR),
            new ColumnDefinition("balance_cents", DataType.BIGINT),
            new ColumnDefinition("is_active", DataType.BOOLEAN)
        ));

        byte[] bytes = original.serialize();
        TableInfo roundTripped = TableInfo.deserialize(bytes);

        assertEquals(original, roundTripped, "a deserialized TableInfo must equal the original it was serialized from");
        assertEquals("users", roundTripped.getName());
        assertEquals(7, roundTripped.getFirstPageId());
        assertEquals(4, roundTripped.getColumns().size());
        assertEquals(DataType.VARCHAR, roundTripped.getColumns().get(1).getType());
    }

    public void testSingleColumnTableRoundTrips() {
        TableInfo original = new TableInfo("counters", 0, List.of(
            new ColumnDefinition("count", DataType.INTEGER)
        ));

        TableInfo roundTripped = TableInfo.deserialize(original.serialize());

        assertEquals(original, roundTripped);
    }

    public void testColumnsListIsImmutable() {
        TableInfo info = new TableInfo("t", 0, List.of(new ColumnDefinition("a", DataType.INTEGER)));
        assertThrows(UnsupportedOperationException.class,
            () -> info.getColumns().add(new ColumnDefinition("b", DataType.INTEGER)),
            "callers must not be able to mutate a table's schema through the getter");
    }
}
