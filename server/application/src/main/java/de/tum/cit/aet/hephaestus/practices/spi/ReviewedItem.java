package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The item at a version that a review setup reviews (ADR 0167 decision 1). */
public sealed interface ReviewedItem {

    /**
     * A repository at a commit.
     *
     * @param ref the branch; null reviews the monitored default branch
     * @param commit the commit that the requester expects; null pins the head at creation, and the server refuses a
     *     commit that is not the head
     */
    record RepositoryCommit(
            long repositoryItemId,
            @Nullable String ref,
            @Nullable String commit) implements ReviewedItem {}

    /** A version that a team submitted, by its {@code team_item_version} id. */
    record SubmittedVersion(UUID versionId) implements ReviewedItem {}
}
