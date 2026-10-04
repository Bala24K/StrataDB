package com.strata.parser.ast;

/**
 * The data types the parser understands in a {@code CREATE TABLE} column list. Mirrors
 * {@link com.strata.catalog.DataType}'s four values exactly, but is deliberately a separate
 * type: the parser produces pure syntax, with no dependency on the catalog module. Translating
 * an {@code ast.ColumnType} into a real {@code catalog.DataType} is a later phase's job (the
 * executor, when it actually calls {@code Catalog.createTable}). See the Phase 4 docs for the
 * full reasoning.
 */
public enum ColumnType {
    INTEGER,
    BIGINT,
    VARCHAR,
    BOOLEAN
}
