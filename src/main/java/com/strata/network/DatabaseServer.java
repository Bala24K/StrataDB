package com.strata.network;

import com.strata.engine.Database;
import com.strata.executor.ExecutionResult;
import com.strata.transaction.Session;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Makes one StrataDB database file reachable over TCP by more than one independent client
 * process at once — the actual thing "networking" buys over everything built so far. Every
 * earlier phase already supported multiple {@code Session}s sharing one database (that's what
 * Phase 8's lock manager and Phase 9's buffer pool were built to coordinate); the only thing
 * genuinely new here is that a {@code Session} no longer has to live in the same process as its
 * caller. Each accepted TCP connection gets its own thread and its own {@code Session},
 * constructed against the exact same shared {@code BufferPool}/{@code LockManager}/
 * {@code WriteAheadLog} every earlier phase's multi-session tests already used — this class is
 * mostly plumbing, not new database logic.
 *
 * <h2>One thread per connection, on purpose</h2>
 * This is a classic blocking-I/O, thread-per-connection server, not an NIO event loop. It's
 * simpler to read and reason about, and it's a completely legitimate real design for a server
 * handling a modest number of concurrent connections — which is the right trade-off for this
 * project's scope. An event-loop/async design would scale to far more simultaneous connections
 * with less memory overhead; that's real, valuable future work, not a correctness gap.
 *
 * <h2>See {@link Protocol} for the wire format.</h2>
 */
public final class DatabaseServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final Database database;
    private volatile boolean running = true;

    private DatabaseServer(ServerSocket serverSocket, Database database) {
        this.serverSocket = serverSocket;
        this.database = database;
    }

    /**
     * Opens (bootstrapping if it doesn't already exist) {@code dbFile} and starts listening on
     * {@code port}. Pass {@code 0} for {@code port} to let the OS assign a free one — see
     * {@link #getPort()} to find out which.
     */
    public static DatabaseServer start(Path dbFile, int port) throws IOException {
        return start(dbFile, port, Database.Options.defaults());
    }

    /** Like {@link #start(Path, int)}, with explicit options (tests use a short lock timeout). */
    public static DatabaseServer start(Path dbFile, int port, Database.Options options) throws IOException {
        Database database = Database.open(dbFile, options);
        try {
            ServerSocket serverSocket = new ServerSocket(port);
            DatabaseServer server = new DatabaseServer(serverSocket, database);
            server.startAcceptLoop();
            return server;
        } catch (IOException | RuntimeException e) {
            database.close();
            throw e;
        }
    }

    /** The database this server serves. */
    public Database database() {
        return database;
    }

    /** The actual port this server is listening on — useful when constructed with port {@code 0}. */
    public int getPort() {
        return serverSocket.getLocalPort();
    }

    private void startAcceptLoop() {
        Thread acceptThread = new Thread(this::acceptLoop, "stratadb-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                Thread handler = new Thread(() -> handleClient(client), "stratadb-client-" + client.getPort());
                handler.setDaemon(true);
                handler.start();
            } catch (IOException e) {
                if (running) {
                    // The listening socket failed for a reason other than us shutting it down
                    // deliberately; nothing to recover to here, so just stop accepting further
                    // connections rather than spin on a broken socket.
                    break;
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        // The session is closed however the connection ends (QUIT, EOF, socket error): closing a
        // shared-resource session rolls back any transaction the client left open and releases
        // its locks. Without this, a client that disconnected mid-transaction held its locks
        // forever and every other client timed out on those tables until a server restart.
        try (socket;
             Session session = database.newSession();
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)) {

            String line;
            while ((line = in.readLine()) != null) {
                String sql = line.trim();
                if (sql.isEmpty()) {
                    continue;
                }
                if (sql.equalsIgnoreCase("QUIT")) {
                    break;
                }
                try {
                    ExecutionResult result = session.execute(sql);
                    out.write(Protocol.encode(result));
                } catch (Exception e) {
                    out.write(Protocol.encodeError(e));
                }
                out.flush();
            }
        } catch (IOException e) {
            // The client disconnected or the connection otherwise failed; nothing more to do
            // for this one connection — other clients are unaffected.
        }
    }

    @Override
    public void close() throws IOException {
        running = false;
        serverSocket.close();
        database.close();
    }
}
