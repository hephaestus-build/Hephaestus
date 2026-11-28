package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Whether advice revisions still have their text, for the conversation basis that names them (#5278). Neither method
 * returns text.
 */
public interface AdviceRevisionTexts {
    /**
     * Locks the revisions of the workspace among {@code revisionIds} that still have their text, in id order, and returns
     * them. A read that hands advice to a conversation calls it before it takes the conversation tree's lock, as the
     * redaction locks the revisions before the trees ({@link AdviceErasureListener}).
     */
    List<UUID> lockWithText(long workspaceId, Collection<UUID> revisionIds);

    /** Whether a revision of the workspace among {@code revisionIds} lost its text. */
    boolean anyWithoutText(long workspaceId, Collection<UUID> revisionIds);
}
