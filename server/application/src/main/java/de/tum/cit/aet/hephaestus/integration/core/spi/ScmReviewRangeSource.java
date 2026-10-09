package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.Optional;

/** Reads a provider-owned diff pair without changing the queued review occasion. */
public interface ScmReviewRangeSource {
    IntegrationKind kind();

    Optional<ReviewRange> read(long workspaceId, String repository, int number);

    record ReviewRange(long repositoryNativeId, long pullRequestNativeId, String head, String base) {}
}
