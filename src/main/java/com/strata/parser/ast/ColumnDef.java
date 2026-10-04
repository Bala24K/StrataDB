package com.strata.parser.ast;

/** One column in a parsed {@code CREATE TABLE}'s column list: its name and declared type. */
public record ColumnDef(String name, ColumnType type) {
}
