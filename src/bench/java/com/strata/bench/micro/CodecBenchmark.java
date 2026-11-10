package com.strata.bench.micro;

import com.strata.catalog.ColumnDefinition;
import com.strata.catalog.DataType;
import com.strata.catalog.TableInfo;
import com.strata.executor.Row;
import com.strata.executor.RowSerializer;
import com.strata.parser.Parser;
import com.strata.parser.ast.Statement;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Per-statement and per-row CPU costs: SQL parsing and row (de)serialization. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class CodecBenchmark {

    private final String selectSql = "SELECT * FROM t WHERE id = 4321 AND val > 10;";
    private final String insertSql = "INSERT INTO t VALUES (4321, 133951, 'xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx');";
    private final TableInfo schema = new TableInfo("t", 1, List.of(
        new ColumnDefinition("id", DataType.INTEGER),
        new ColumnDefinition("val", DataType.BIGINT),
        new ColumnDefinition("pad", DataType.VARCHAR)));
    private final Row row = new Row(Arrays.asList(4321, 133951L, "x".repeat(40)));
    private final byte[] encoded = RowSerializer.serialize(row, schema);

    @Benchmark
    public Statement parseSelect() {
        return Parser.parse(selectSql);
    }

    @Benchmark
    public Statement parseInsert() {
        return Parser.parse(insertSql);
    }

    @Benchmark
    public byte[] serializeRow() {
        return RowSerializer.serialize(row, schema);
    }

    @Benchmark
    public Row deserializeRow() {
        return RowSerializer.deserialize(encoded, schema);
    }

    @Benchmark
    public Row deserializeRowInPlace() {
        return RowSerializer.deserialize(encoded, 0, encoded.length, schema);
    }

    private final com.strata.executor.ExecutionResult oneRow =
        new com.strata.executor.ExecutionResult.Rows(List.of("id", "val", "pad"), List.of(row));
    private final com.strata.executor.ExecutionResult hundredRows =
        new com.strata.executor.ExecutionResult.Rows(List.of("id", "val", "pad"), java.util.Collections.nCopies(100, row));

    /** Text-protocol encoding of a one-row result: what a binary protocol could save per point query. */
    @Benchmark
    public String encodeOneRowResult() {
        return com.strata.network.Protocol.encode(oneRow);
    }

    @Benchmark
    public String encodeHundredRowResult() {
        return com.strata.network.Protocol.encode(hundredRows);
    }
}
