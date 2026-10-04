package com.strata.executor;

import java.util.List;

/**
 * One row's worth of runtime values, in the same order as its table's columns. Each element is
 * one of {@code Integer} (a 32-bit {@code INTEGER}), {@code Long} (a 64-bit {@code BIGINT}),
 * {@code String} ({@code VARCHAR}), {@code Boolean} ({@code BOOLEAN}), or {@code null} (SQL
 * {@code NULL}, valid for any column since there's no {@code NOT NULL} constraint yet).
 *
 * <p>Deliberately schema-agnostic on its own — a {@code Row} doesn't know column names, only
 * positions. Resolving a column name to a position always goes through the table's
 * {@link com.strata.catalog.TableInfo}, in the {@link Executor}.
 */
public record Row(List<Object> values) {

    public Object get(int index) {
        return values.get(index);
    }
}
