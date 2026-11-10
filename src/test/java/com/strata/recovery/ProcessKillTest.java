package com.strata.recovery;

import static com.strata.testutil.Assert.*;

import com.strata.engine.Database;
import com.strata.executor.ExecutionResult;
import com.strata.executor.Row;
import com.strata.transaction.Session;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Real crash test: runs {@link CrashWorkload} in a separate JVM, force-kills it (SIGKILL on
 * Unix, TerminateProcess on Windows) at a random moment, then reopens the database through
 * recovery and checks:
 * <ul>
 *   <li><b>Durability:</b> every batch the child acknowledged after COMMIT is present.</li>
 *   <li><b>Atomicity:</b> no partial batch: the row count is a multiple of the batch size and
 *       batches form a gap-free prefix (at most one unacknowledged batch beyond the last ACK,
 *       whose COMMIT finished but whose ACK line was never printed).</li>
 *   <li><b>Index consistency:</b> every surviving row is found through the hash index.</li>
 * </ul>
 * Limitation: killing a process leaves the OS page cache intact, so this does not simulate
 * power loss; {@link CrashInjectionTest} covers failures at the data-file write level.
 */
public class ProcessKillTest {

    public void testKilledProcessRecoversEveryAcknowledgedCommitAndNoPartialOne() throws Exception {
        Random rnd = new Random(42);
        for (int round = 0; round < 3; round++) {
            runRound(150 + rnd.nextInt(400));
        }
    }

    private static void runRound(long killAfterMillis) throws Exception {
        Path db = Files.createTempFile("stratadb-kill", ".db");
        Files.deleteIfExists(db);
        Path wal = Path.of(db + ".wal");
        try {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                CrashWorkload.class.getName(), db.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();

            int lastAck = -1;
            try (BufferedReader out = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                String first = out.readLine();
                assertEquals("READY", first, "child workload failed to start");
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(killAfterMillis);
                String line;
                while (System.nanoTime() < deadline && (line = out.readLine()) != null) {
                    if (line.startsWith("ACK ")) lastAck = Integer.parseInt(line.substring(4));
                }
                child.destroyForcibly();
                child.waitFor(10, TimeUnit.SECONDS);
                // drain anything the child printed before it died: those commits were acknowledged too
                while ((line = out.readLine()) != null) {
                    if (line.startsWith("ACK ")) lastAck = Integer.parseInt(line.substring(4));
                }
            }
            assertTrue(lastAck >= 0, "the child should have committed at least one batch before being killed");

            try (Database reopened = Database.open(db);
                 Session s = reopened.newSession()) {
                List<Row> rows = ((ExecutionResult.Rows) s.execute("SELECT * FROM t;")).rows();
                int batch = CrashWorkload.BATCH;
                assertEquals(0, rows.size() % batch, "a partially committed batch survived: " + rows.size() + " rows");
                int batches = rows.size() / batch;
                assertTrue(batches == lastAck + 1 || batches == lastAck + 2,
                    "expected " + (lastAck + 1) + " acknowledged batches (+1 unacknowledged at most), found " + batches);
                boolean[] seen = new boolean[rows.size()];
                for (Row row : rows) {
                    int id = (Integer) row.get(0);
                    assertTrue(id >= 0 && id < rows.size() && !seen[id], "ids must be a gap-free, duplicate-free prefix; bad id " + id);
                    seen[id] = true;
                }
                for (int id = 0; id < rows.size(); id++) {
                    assertEquals(1, ((ExecutionResult.Rows) s.execute("SELECT * FROM t WHERE id = " + id + ";")).rows().size(),
                        "index lookup for id " + id);
                }
            }
        } finally {
            Files.deleteIfExists(db);
            Files.deleteIfExists(wal);
        }
    }

    @SuppressWarnings("unused")
    private static String classpathSeparator() {
        return File.pathSeparator;
    }
}
