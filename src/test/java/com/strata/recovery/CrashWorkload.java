package com.strata.recovery;

import com.strata.engine.Database;
import com.strata.transaction.Session;

import java.nio.file.Path;

/**
 * Child-process workload for {@link ProcessKillTest}: commits batches of
 * {@link #BATCH} rows in explicit transactions forever, printing {@code ACK n} after the n-th
 * batch's COMMIT returns. The parent kills this process at a random moment.
 */
public final class CrashWorkload {

    static final int BATCH = 5;

    public static void main(String[] args) throws Exception {
        Path db = Path.of(args[0]);
        String pad = "w".repeat(200);
        try (Database database = Database.open(db);
             Session s = database.newSession()) {
            s.execute("CREATE TABLE t (id INTEGER, batch INTEGER, pad VARCHAR);");
            s.execute("CREATE INDEX t_id ON t (id);");
            System.out.println("READY");
            System.out.flush();
            for (int batch = 0; ; batch++) {
                s.execute("BEGIN;");
                for (int i = 0; i < BATCH; i++) {
                    int id = batch * BATCH + i;
                    s.execute("INSERT INTO t VALUES (" + id + ", " + batch + ", '" + pad + "');");
                }
                s.execute("COMMIT;");
                System.out.println("ACK " + batch);
                System.out.flush();
            }
        }
    }
}
