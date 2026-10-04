package com.strata.storage;

import java.util.Objects;

/**
 * The address of a single record: which page it lives on, and which slot within that page's
 * slot directory. This is the "pointer" type foreshadowed in the Phase 0 docs — every future
 * index (hash index, B+Tree) will ultimately store {@code RecordId}s as the thing it looks up.
 *
 * <p>Immutable and just two ints — cheap to create, cheap to pass around, cheap to store
 * millions of in an index later.
 */
public final class RecordId {

    private final int pageId;
    private final int slotIndex;

    public RecordId(int pageId, int slotIndex) {
        this.pageId = pageId;
        this.slotIndex = slotIndex;
    }

    public int getPageId() {
        return pageId;
    }

    public int getSlotIndex() {
        return slotIndex;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RecordId other)) return false;
        return pageId == other.pageId && slotIndex == other.slotIndex;
    }

    @Override
    public int hashCode() {
        return Objects.hash(pageId, slotIndex);
    }

    @Override
    public String toString() {
        return "RecordId(page=" + pageId + ", slot=" + slotIndex + ")";
    }
}
