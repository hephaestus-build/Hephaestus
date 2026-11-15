package de.tum.cit.aet.hephaestus.practices.review.dto;

import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record DeliveredWorkFeedbackCopyDTO(
        @NonNull UUID id,
        @NonNull FeedbackCopyKind kind,
        @Nullable String externalRef,
        @Nullable String externalUrl,
        @Nullable String path,
        @Nullable Integer startLine,
        @Nullable Integer endLine,
        @Nullable CitationSide side,
        @NonNull Instant postedAt) {}
