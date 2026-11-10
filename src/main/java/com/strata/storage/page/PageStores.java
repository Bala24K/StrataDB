package com.strata.storage.page;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

/**
 * A small shared utility: apply a batch of {@code (pageId, Page)} writes to a {@link PageStore},
 * transparently growing it (via {@link PageStore#allocatePage()}) for any page id that doesn't
 * exist in the target yet.
 *
 * <p>This logic started out living only inside {@code Transaction.commit()}, but Phase 11's
 * write-ahead log recovery needs the exact same behavior — replaying a committed transaction's
 * writes against the real {@code DiskManager} directly, including any pages that transaction
 * had newly allocated. Rather than duplicate the (subtly easy to get wrong — see
 * {@link #applyAll}'s docs) growth logic in two places, both now call this one method.
 */
public final class PageStores {

    private PageStores() {}

    /**
     * Applies every entry in {@code writes} to {@code target}, in ascending page-id order, first
     * growing {@code target} (zero-filled) until every page id in the batch exists.
     */
    public static void applyAll(PageStore target, Map<Integer, Page> writes) throws IOException {
        for (Map.Entry<Integer, Page> entry : new TreeMap<>(writes).entrySet()) {
            int pageId = entry.getKey();
            while (pageId >= target.getNumPages()) {
                // Unreachable for live commits (Transaction reserves page ids in the real store
                // at allocation time), but needed by WAL recovery against a file whose growth
                // never reached disk. Any gap — ids reserved by transactions that never
                // committed — is simply zero-filled.
                target.allocatePage();
            }
            target.writePage(pageId, entry.getValue());
        }
    }
}
