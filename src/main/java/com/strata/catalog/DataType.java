package com.strata.catalog;

/**
 * The data types StrataDB knows about. Deliberately small for now — just enough to build and
 * exercise the catalog and (in later phases) a query executor. Growing this enum is cheap and
 * safe: nothing outside the catalog and the future record (de)serializer switches on it.
 */
public enum DataType {
    INTEGER,
    BIGINT,
    VARCHAR,
    BOOLEAN
}
