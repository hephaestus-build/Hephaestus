package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * The basis of a derived record: the sources that it read, and whether one of them was removed since (ADR 0128).
 *
 * <p>A derived record cites its sources in the transaction that writes it, as the owner that the transaction binds
 * ({@link OwnerScope}). The removal of a source writes its {@code source_tombstone} row in the transaction of the
 * removal. So from that commit every record that cites the source is stale, for every person in the workspace, and no
 * row of another person changes. A read checks validity before a derived record shapes an answer: a record is stale when
 * it cites a removed source, or cites a stale record.
 */
public interface BasisInvalidation {
    /** A record in {@code basis_edge}: a source, or a record derived from sources. */
    record Ref(BasisKind kind, UUID id) {}

    /** Records that {@code dependent} read {@code sources}. Citing a source again changes nothing. */
    void cite(long workspaceId, Ref dependent, Collection<Ref> sources);

    /** The records among {@code dependents} that a removed source reaches, directly or through a stale record. */
    Set<Ref> stale(long workspaceId, Collection<Ref> dependents);
}
