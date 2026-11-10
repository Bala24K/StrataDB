package com.strata.index;

import com.strata.storage.RecordId;

import java.io.IOException;
import java.util.List;

/**
 * What the executor needs from any index: maintain {@code (key, RecordId)} entries and find the
 * RecordIds for a key. Implemented by the hash index and the B+Tree; only the B+Tree adds
 * ordered range scans ({@code BPlusTree.range}).
 *
 * <p>Keys are the column's runtime values ({@code Integer}, {@code Long}, {@code String},
 * {@code Boolean}); {@code null} is never indexed. Duplicate keys are allowed.
 */
public interface SecondaryIndex {

    void insert(Object key, RecordId rid) throws IOException;

    /** Removes the entry {@code (key, rid)}; returns false if it wasn't there. */
    boolean delete(Object key, RecordId rid) throws IOException;

    /** Every RecordId indexed under {@code key}. */
    List<RecordId> search(Object key) throws IOException;
}
