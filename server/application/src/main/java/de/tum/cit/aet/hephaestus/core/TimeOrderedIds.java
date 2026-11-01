package de.tum.cit.aet.hephaestus.core;

import java.util.UUID;
import org.hibernate.id.uuid.UuidVersion7Strategy;

/**
 * Ids in the order they are made (UUIDv7), for tables that a conversation or a review inserts into while it runs. A
 * random id would dirty a random leaf of the id index, and the first change to a page after a checkpoint writes the
 * whole page to the write-ahead log. Successive ids of one process increase, so inserts go to the right-hand leaf. The
 * id carries its creation millisecond, which every reader of the record already sees.
 */
public final class TimeOrderedIds {

    private TimeOrderedIds() {}

    public static UUID next() {
        // The strategy reads no session, so a caller can assign the id before the row is persisted.
        return UuidVersion7Strategy.INSTANCE.generateUuid(null);
    }
}
