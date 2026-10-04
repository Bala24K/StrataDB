package com.strata.network;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A minimal client for {@link DatabaseServer}, deliberately kept small: it's really just a
 * socket, a line reader, and a line writer. That's the whole point of a simple, text-based wire
 * protocol (see {@link Protocol}) — any tool that can send text over a TCP connection (a real
 * client library, or literally {@code nc localhost <port>}) can talk to StrataDB.
 */
public final class DatabaseClient implements AutoCloseable {

    private final Socket socket;
    private final BufferedReader in;
    private final Writer out;

    public DatabaseClient(String host, int port) throws IOException {
        this.socket = new Socket(host, port);
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
    }

    /** Sends one SQL statement and returns the server's response lines, excluding the trailing {@code END} sentinel. */
    public List<String> execute(String sql) throws IOException {
        out.write(sql);
        out.write("\n");
        out.flush();

        List<String> lines = new ArrayList<>();
        String line;
        while ((line = in.readLine()) != null) {
            if (line.equals(Protocol.END)) {
                break;
            }
            lines.add(line);
        }
        return lines;
    }

    public void quit() throws IOException {
        out.write("QUIT\n");
        out.flush();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
