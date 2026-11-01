package de.tum.cit.aet.hephaestus.mentor;

import java.util.UUID;
import org.hibernate.id.uuid.UuidVersion7Strategy;

/**
 * Ids for conversations, turns and entries, in the order they are made (UUIDv7). A streamed answer inserts rows into
 * these tables; a random id would dirty a random leaf of every id index, and the first change to a page after a
 * checkpoint writes the whole page to the write-ahead log. Successive ids of one process increase, so inserts go to
 * the right-hand leaf. The id carries its creation millisecond, which every reader of the record already sees.
 */
final class MentorIds {

    private MentorIds() {}

    static UUID next() {
        // The strategy reads no session; the entities assign their id before they are persisted.
        return UuidVersion7Strategy.INSTANCE.generateUuid(null);
    }
}
