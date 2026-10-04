package com.strata.executor;

import static com.strata.testutil.Assert.*;

import com.strata.catalog.ColumnDefinition;
import com.strata.catalog.DataType;
import com.strata.catalog.TableInfo;

import java.util.Arrays;
import java.util.List;

public class RowSerializerTest {

    private static TableInfo schema() {
        return new TableInfo("t", 0, List.of(
            new ColumnDefinition("id", DataType.INTEGER),
            new ColumnDefinition("name", DataType.VARCHAR),
            new ColumnDefinition("balance", DataType.BIGINT),
            new ColumnDefinition("active", DataType.BOOLEAN)
        ));
    }

    public void testRoundTripAllTypesNoNulls() {
        TableInfo schema = schema();
        Row row = new Row(Arrays.asList(42, "Alice", 1_000_000_000_000L, true));

        Row back = RowSerializer.deserialize(RowSerializer.serialize(row, schema), schema);

        assertEquals(row, back);
    }

    public void testRoundTripWithSomeNulls() {
        TableInfo schema = schema();
        Row row = new Row(Arrays.asList(1, null, null, false));

        Row back = RowSerializer.deserialize(RowSerializer.serialize(row, schema), schema);

        assertEquals(row, back);
    }

    public void testRoundTripAllNulls() {
        TableInfo schema = schema();
        Row row = new Row(Arrays.asList(null, null, null, null));

        Row back = RowSerializer.deserialize(RowSerializer.serialize(row, schema), schema);

        assertEquals(row, back);
    }

    public void testMismatchedValueCountThrows() {
        TableInfo schema = schema();
        Row row = new Row(List.of(1)); // schema has 4 columns

        assertThrows(IllegalArgumentException.class, () -> RowSerializer.serialize(row, schema));
    }
}
