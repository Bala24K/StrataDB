package com.strata.network;

import com.strata.executor.ExecutionResult;
import com.strata.executor.Row;

import java.util.stream.Collectors;

/**
 * The wire format {@link DatabaseServer} speaks: a simple, line-oriented, text protocol. A
 * client sends one SQL statement per line; the server replies with one or more lines describing
 * the result, always terminated by a literal {@code END} line so a line-reading client knows
 * exactly where a response finishes on a byte stream that has no other message boundaries.
 *
 * <pre>
 *   SELECT * FROM users;                    (client sends)
 *
 *   OK ROWS 2                               (server replies)
 *   id	name
 *   1	Alice
 *   2	Bob
 *   END
 * </pre>
 *
 * <h2>A deliberate simplification: text, not typed/length-prefixed fields</h2>
 * Values are rendered with plain {@code toString()}, tab-separated, and {@code NULL} is written
 * as the literal text {@code NULL}. This is simple and human-readable — you can talk to
 * StrataDB with nothing more than {@code nc localhost <port>} — but it's genuinely ambiguous: a
 * real {@code VARCHAR} value that happens to contain a tab character, or that happens to be the
 * text {@code "NULL"}, can't be told apart from the protocol's own delimiters. A production wire
 * protocol (PostgreSQL's, for instance) avoids this with typed, length-prefixed binary fields.
 * That's real, valuable future work, traded away here for a protocol simple enough to read by
 * eye and to drive from a bare TCP connection with no client library at all.
 */
final class Protocol {

    static final String END = "END";

    private Protocol() {}

    static String encode(ExecutionResult result) {
        StringBuilder text = new StringBuilder();
        switch (result) {
            case ExecutionResult.Rows rows -> {
                text.append("OK ROWS ").append(rows.rows().size()).append('\n');
                text.append(String.join("\t", rows.columnNames())).append('\n');
                for (Row row : rows.rows()) {
                    text.append(row.values().stream().map(Protocol::encodeValue).collect(Collectors.joining("\t"))).append('\n');
                }
            }
            case ExecutionResult.TableCreated r -> text.append("OK TABLE_CREATED ").append(r.tableName()).append('\n');
            case ExecutionResult.IndexCreated r -> text.append("OK INDEX_CREATED ").append(r.indexName()).append('\n');
            case ExecutionResult.RowsInserted r -> text.append("OK ROWS_INSERTED ").append(r.count()).append('\n');
            case ExecutionResult.RowsUpdated r -> text.append("OK ROWS_UPDATED ").append(r.count()).append('\n');
            case ExecutionResult.RowsDeleted r -> text.append("OK ROWS_DELETED ").append(r.count()).append('\n');
            case ExecutionResult.TransactionStarted ignored -> text.append("OK TRANSACTION_STARTED").append('\n');
            case ExecutionResult.TransactionCommitted ignored -> text.append("OK TRANSACTION_COMMITTED").append('\n');
            case ExecutionResult.TransactionRolledBack ignored -> text.append("OK TRANSACTION_ROLLED_BACK").append('\n');
        }
        text.append(END).append('\n');
        return text.toString();
    }

    static String encodeError(Throwable t) {
        String message = t.getMessage() == null ? t.toString() : t.getMessage();
        return "ERROR " + t.getClass().getSimpleName() + ": " + message.replace("\n", " ").replace("\r", " ")
            + '\n' + END + '\n';
    }

    private static String encodeValue(Object value) {
        return value == null ? "NULL" : value.toString();
    }
}
