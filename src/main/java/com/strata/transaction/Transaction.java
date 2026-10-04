package com.strata.transaction;

import com.strata.recovery.WriteAheadLog;
import com.strata.storage.page.Page;
import com.strata.storage.page.PageStore;
import com.strata.storage.page.PageStores;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * A {@link PageStore} that sits in front of a real one and buffers every write in an in-memory
 * overlay instead of touching disk — until {@link #commit()} replays the whole buffer through
 * the real store at once, or {@link #rollback()} throws the buffer away untouched.
 *
 * <h2>Why this is the right seam for transactions</h2>
 * Phase 6 extracted {@link PageStore} as the interface {@code HeapTable} and {@code Catalog}
 * actually depend on, rather than the concrete {@code DiskManager}. That decision pays off
 * directly here: a {@code Transaction} implements the exact same interface, so
 * {@code HeapTable.create(txn)} or {@code Catalog.open(txn)} work completely unmodified — as
 * far as they know, they're just talking to a store. Neither class needed to change a single
 * line for transactions to exist. This is the architecture's dependency-points-downward
 * principle actually paying rent.
 *
 * <h2>Reads and writes, inside one transaction</h2>
 * {@link #readPage} checks the in-memory overlay first — if this transaction has already
 * written page 7, it reads back *that* buffered version, not what's on disk (read-your-own-
 * writes). Anything not yet touched by this transaction falls through to the underlying store,
 * so a transaction always sees the latest already-*committed* state for everything it hasn't
 * changed itself, plus its own uncommitted changes. Nothing this transaction writes is visible
 * to anyone reading the underlying store directly until {@link #commit()} runs.
 *
 * <h2>Atomicity and Durability, and where each one actually comes from</h2>
 * <b>Atomicity</b> (all of a transaction's changes take effect, or none do) comes directly from
 * buffering: {@link #commit()} either successfully replays every buffered page, or (per the
 * write-ahead log described below) can always finish doing so after a restart.
 * {@link #rollback()} is trivial: since nothing was ever written to the real store, "undoing"
 * the transaction just means discarding the buffer.
 *
 * <p><b>Durability</b> (a committed transaction survives a crash) comes from two decisions
 * layered on top of each other. Phase 0's {@code DiskManager.writePage()} fsyncs on every
 * write, so once a page is actually written through, it's durable. Phase 11 closes the gap that
 * was left even so: if {@code wal} is supplied, {@link #commit()} first calls
 * {@link WriteAheadLog#logCommit}, which durably logs (and fsyncs) exactly what this transaction
 * is about to write, *before* any of it reaches the real store. If the process crashes partway
 * through applying those writes, {@link WriteAheadLog#recover} replays the log the next time the
 * database opens and finishes the job — see that class's docs for the full reasoning, including
 * why only "redo" (not "undo") is needed here.
 *
 * <h2>Concurrency: coordinated by {@code Session} + the Lock Manager, not by this class</h2>
 * A {@code Transaction} doesn't enforce anything about concurrent access on its own — that's
 * deliberately {@code com.strata.lock.LockManager}'s job (Phase 8), orchestrated by
 * {@code Session}. An earlier version of this class tried to catch misuse cheaply by checking
 * whether the underlying store's total page count had drifted since this transaction began, and
 * refusing to commit if so. That check was removed once Phase 8 made it actively wrong: once
 * two transactions can legitimately run at once — even against two completely different tables
 * — the shared store's page count routinely changes for reasons that have nothing to do with
 * *this* transaction's own data. A check that can't distinguish "another transaction safely grew
 * an unrelated table" from "something bypassed the transaction system entirely" isn't a safety
 * net, it's a source of spurious failures under real concurrent load — which is exactly how it
 * was found (see the Phase 12 docs for the failing multi-client test that caught it). Anyone
 * using {@code Transaction} directly, without going through {@code Session}'s locking, is
 * responsible for ensuring only one transaction writes to a given table at a time.
 */
public final class Transaction implements PageStore {

    private final PageStore underlying;
    private final WriteAheadLog wal;
    private final Map<Integer, Page> overlay = new HashMap<>();
    private int overlayNumPages;
    private boolean finished = false;

    public Transaction(PageStore underlying) {
        this(underlying, null);
    }

    /**
     * Phase 11: {@code wal}, if given, is logged durably ({@link WriteAheadLog#logCommit})
     * <em>before</em> {@link #commit()} applies anything to {@code underlying} — see
     * {@link WriteAheadLog}'s own docs for why that ordering is what makes recovery possible.
     * {@code null} keeps the pre-Phase-11 behavior (no logging), which every earlier phase's
     * tests still rely on.
     */
    public Transaction(PageStore underlying, WriteAheadLog wal) {
        this.underlying = underlying;
        this.wal = wal;
        this.overlayNumPages = underlying.getNumPages();
    }

    @Override
    public Page readPage(int pageId) throws IOException {
        requireActive();
        Page buffered = overlay.get(pageId);
        return (buffered != null) ? buffered : underlying.readPage(pageId);
    }

    @Override
    public void writePage(int pageId, Page page) throws IOException {
        requireActive();
        overlay.put(pageId, page); // buffered only — nothing touches the real store until commit()
    }

    @Override
    public int allocatePage() throws IOException {
        requireActive();
        int newPageId = overlayNumPages++;
        overlay.put(newPageId, new Page()); // reserve it as an empty page, same as a real allocatePage() would
        return newPageId;
    }

    @Override
    public int getNumPages() {
        return overlayNumPages;
    }

    /** Applies every buffered write to the underlying store, in page-id order, and fsyncs it. */
    public void commit() throws IOException {
        requireActive();
        if (wal != null && !overlay.isEmpty()) {
            // Durably logged BEFORE any of these writes reach the real store — this ordering
            // is what makes it possible to finish an interrupted commit after a crash.
            wal.logCommit(overlay);
        }
        PageStores.applyAll(underlying, overlay);
        finished = true;
    }

    /** Discards every buffered write. Since nothing was ever applied to the underlying store, there's nothing to undo. */
    public void rollback() {
        requireActive();
        overlay.clear();
        finished = true;
    }

    private void requireActive() {
        if (finished) {
            throw new IllegalStateException("This transaction has already been committed or rolled back");
        }
    }
}
