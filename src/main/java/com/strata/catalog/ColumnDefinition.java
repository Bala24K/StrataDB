package com.strata.catalog;

import java.util.Objects;

/** A single column in a table's schema: its name and its {@link DataType}. */
public final class ColumnDefinition {

    private final String name;
    private final DataType type;

    public ColumnDefinition(String name, DataType type) {
        this.name = Objects.requireNonNull(name, "column name");
        this.type = Objects.requireNonNull(type, "column type");
    }

    public String getName() {
        return name;
    }

    public DataType getType() {
        return type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ColumnDefinition other)) return false;
        return name.equals(other.name) && type == other.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type);
    }

    @Override
    public String toString() {
        return name + " " + type;
    }
}
