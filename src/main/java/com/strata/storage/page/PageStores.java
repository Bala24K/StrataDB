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
     * Applies every entry in {@code writes} to {@code target}, in ascending page-id order.
     * Ascending order matters: any page id at or beyond {@code target}'s current size is
     * assumed to be a page the original writer allocated itself, and is recreated here via
     * {@code target.allocatePage()} — which only lands on the right id if pages are grown one
     * at a time, in the same order they were originally handed out.
     */
    public static void applyAll(PageStore target, Map<Integer, Page> writes) throws IOException {
        for (Map.Entry<Integer, Page> entry : new TreeMap<>(writes).entrySet()) {
            int pageId = entry.getKey();
            if (pageId >= target.getNumPages()) {
                int allocated = target.allocatePage();
                if (allocated != pageId) {
                    throw new IllegalStateException(
                        "Internal invariant violated: page numbering diverged while applying a batch of writes");
                }
            }
            target.writePage(pageId, entry.getValue());
        }
    }
}
