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
 * <h2>Framing: the row count, not the {@code END} line, delimits a result set</h2>
 * A client must read exactly the column-header line plus {@code n} row lines after
 * {@code OK ROWS n}, then the {@code END} line. An earlier client scanned for the first line
 * equal to {@code END} instead, so a row whose only value was the string {@code 'END'} ended the
 * response early, and every later response on that connection was off by one.
 *
 * <h2>Escaping</h2>
 * Values are tab-separated, one row per line, so inside a value backslash, tab, newline and
 * carriage return are escaped as {@code \\}, {@code \t}, {@code \n} and {@code \r} (the same
 * convention as PostgreSQL's text COPY format). Ordinary values are sent unchanged.
 *
 * <h2>Remaining limitation</h2>
 * {@code NULL} is the literal text {@code NULL}, indistinguishable from a VARCHAR whose value
 * is the four letters {@code NULL}. A typed, length-prefixed binary protocol removes this and the
 * parsing cost of text altogether; this text protocol stays because you can drive it from
 * {@code nc localhost <port>}.
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

    static String encodeValue(Object value) {
        return value == null ? "NULL" : escape(value.toString());
    }

    static String escape(String s) {
        StringBuilder out = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String replacement = switch (c) {
                case '\\' -> "\\\\";
                case '\t' -> "\\t";
                case '\n' -> "\\n";
                case '\r' -> "\\r";
                default -> null;
            };
            if (replacement != null && out == null) {
                out = new StringBuilder(s.length() + 8).append(s, 0, i);
            }
            if (out != null) {
                if (replacement != null) out.append(replacement); else out.append(c);
            }
        }
        return out == null ? s : out.toString();
    }

    /** Inverse of {@link #escape}, for clients that want the original value back. */
    public static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                out.append(switch (next) {
                    case 't' -> '\t';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    default -> next;
                });
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
