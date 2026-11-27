package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.Set;

/**
 * Reads one normalizer's submitted documents into citable elements. It is the only part of a team review that knows the
 * tool a snapshot came from: the stored version, the review, its observations and their delivery see elements.
 */
public interface SnapshotIndexer {
    /** The item kinds whose snapshots this indexer reads. */
    Set<ItemKind> kinds();

    /** The normalizer and its version, such as {@code figma-selection/1}. */
    String normalizer();

    /**
     * The same bytes give the same elements in the same order. The caller never accepts elements that a client made.
     *
     * @throws SnapshotRefusedException when this indexer cannot read the whole document; nothing is then stored
     */
    SnapshotIndex index(byte[] normalized);
}
