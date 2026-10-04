package com.strata;

import com.strata.executor.ExecutionResult;
import com.strata.network.DatabaseClient;
import com.strata.network.DatabaseServer;
import com.strata.storage.page.DiskManager;
import com.strata.transaction.Session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

/**
 * Command-line entry point for StrataDB:
 * <ul>
 *   <li>{@code shell <dbFilePath>} — embedded interactive SQL shell against a local database file</li>
 *   <li>{@code server <dbFilePath> [port]} — starts the TCP database server (default port 5432)</li>
 *   <li>{@code client [host] [port]} — interactive SQL client connecting to a running server (default localhost:5432)</li>
 * </ul>
 */
public final class Main {

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }

        String mode = args[0].toLowerCase();
        try {
            switch (mode) {
                case "shell" -> runEmbeddedShell(args);
                case "server" -> runServer(args);
                case "client" -> runClient(args);
                case "help", "--help", "-h" -> printUsage();
                default -> {
                    System.err.println("Unknown command: " + mode);
                    printUsage();
                    System.exit(1);
                }
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("StrataDB - A relational database engine built from first principles");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  shell <db-file>          Open an embedded interactive SQL shell");
        System.out.println("  server <db-file> [port]  Start a multi-client TCP database server (default port: 5432)");
        System.out.println("  client [host] [port]     Connect to a running database server (default: localhost:5432)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -cp out/classes com.strata.Main shell demo.db");
        System.out.println("  java -cp out/classes com.strata.Main server demo.db 5432");
        System.out.println("  java -cp out/classes com.strata.Main client localhost 5432");
    }

    private static void runEmbeddedShell(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: shell <db-file>");
            System.exit(1);
        }

        Path dbPath = Path.of(args[1]);
        boolean isNew = !Files.exists(dbPath) || Files.size(dbPath) == 0;
        DiskManager dm = new DiskManager(dbPath);
        Session session = isNew ? Session.bootstrap(dm) : Session.open(dm);

        System.out.println("StrataDB Embedded Shell (" + (isNew ? "created new" : "opened existing") + " " + dbPath + ")");
        System.out.println("Type SQL statements ending with a semicolon, or 'quit' to exit.\n");

        try (dm; session; Scanner scanner = new Scanner(System.in)) {
            runRepl(sql -> {
                ExecutionResult result = session.execute(sql);
                printExecutionResult(result);
            }, scanner);
        }
    }

    private static void runServer(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: server <db-file> [port]");
            System.exit(1);
        }

        Path dbPath = Path.of(args[1]);
        int port = args.length >= 3 ? Integer.parseInt(args[2]) : 5432;

        try (DatabaseServer server = DatabaseServer.start(dbPath, port)) {
            System.out.println("StrataDB TCP Server listening on port " + server.getPort() + " (database: " + dbPath + ")");
            System.out.println("Press Ctrl+C to stop.");
            Thread.currentThread().join();
        }
    }

    private static void runClient(String[] args) throws IOException {
        String host = args.length >= 2 ? args[1] : "localhost";
        int port = args.length >= 3 ? Integer.parseInt(args[2]) : 5432;

        System.out.println("Connecting to StrataDB server at " + host + ":" + port + "...");
        try (DatabaseClient client = new DatabaseClient(host, port);
             Scanner scanner = new Scanner(System.in)) {
            System.out.println("Connected. Type SQL statements ending with a semicolon, or 'quit' to exit.\n");
            runRepl(sql -> {
                List<String> response = client.execute(sql);
                for (String line : response) {
                    System.out.println(line);
                }
            }, scanner);
            client.quit();
        }
    }

    @FunctionalInterface
    private interface SqlHandler {
        void handle(String sql) throws Exception;
    }

    private static void runRepl(SqlHandler handler, Scanner scanner) {
        StringBuilder sqlBuffer = new StringBuilder();
        while (true) {
            if (sqlBuffer.length() == 0) {
                System.out.print("stratadb> ");
            } else {
                System.out.print("      -> ");
            }
            if (!scanner.hasNextLine()) {
                break;
            }
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.equalsIgnoreCase("quit") || line.equalsIgnoreCase("exit") ||
                line.equalsIgnoreCase("quit;") || line.equalsIgnoreCase("exit;")) {
                break;
            }

            sqlBuffer.append(" ").append(line);
            while (true) {
                String current = sqlBuffer.toString();
                int semi = current.indexOf(';');
                if (semi == -1) {
                    break;
                }
                String stmt = current.substring(0, semi + 1).trim();
                sqlBuffer = new StringBuilder(current.substring(semi + 1).trim());
                if (!stmt.isEmpty()) {
                    try {
                        handler.handle(stmt);
                    } catch (Exception e) {
                        System.err.println("Error: " + e.getMessage());
                    }
                }
            }
        }
    }

    private static void printExecutionResult(ExecutionResult result) {
        switch (result) {
            case ExecutionResult.Rows rows -> {
                System.out.println(String.join("\t", rows.columnNames()));
                System.out.println("-".repeat(Math.max(20, rows.columnNames().size() * 12)));
                for (var row : rows.rows()) {
                    System.out.println(String.join("\t", row.values().stream().map(v -> v == null ? "NULL" : v.toString()).toList()));
                }
                System.out.println("(" + rows.rows().size() + " row" + (rows.rows().size() == 1 ? "" : "s") + ")");
            }
            case ExecutionResult.TableCreated tc -> System.out.println("Table created: " + tc.tableName());
            case ExecutionResult.IndexCreated ic -> System.out.println("Index created: " + ic.indexName());
            case ExecutionResult.RowsInserted ri -> System.out.println("Inserted " + ri.count() + " row" + (ri.count() == 1 ? "" : "s"));
            case ExecutionResult.RowsUpdated ru -> System.out.println("Updated " + ru.count() + " row" + (ru.count() == 1 ? "" : "s"));
            case ExecutionResult.RowsDeleted rd -> System.out.println("Deleted " + rd.count() + " row" + (rd.count() == 1 ? "" : "s"));
            case ExecutionResult.TransactionStarted ignored -> System.out.println("BEGIN");
            case ExecutionResult.TransactionCommitted ignored -> System.out.println("COMMIT");
            case ExecutionResult.TransactionRolledBack ignored -> System.out.println("ROLLBACK");
        }
    }
}
