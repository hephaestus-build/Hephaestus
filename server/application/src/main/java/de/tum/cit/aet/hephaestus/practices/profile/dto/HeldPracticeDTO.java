package de.tum.cit.aet.hephaestus.practices.profile.dto;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** A practice that stands as a strength and whose newest pieces of reviewed work all came back clean. */
@Schema(description = "A practice the developer keeps holding")
public record HeldPracticeDTO(
        @NonNull String practiceSlug,
        @NonNull String practiceName,
        @Nullable String groupSlug,

        @Nullable
        @Schema(
                description = "What the developer keeps doing, in the catalog's words; absent for a practice the"
                        + " catalog does not ship")
        String holdsAs,

        @NonNull @Schema(description = "How many of the newest pieces of work in a row came back clean")
        Integer cleanWork,

        @NonNull @Schema(description = "Artifact kind id most of that work is")
        String workKind,

        @Nullable
        @Schema(
                description = "The provider most of that work lives at, which decides its noun: a GitLab"
                        + " scm.pull_request is a merge request; absent when no run that reviewed the work is left")
        IntegrationKind workProvider,

        @NonNull @Schema(description = "When the oldest piece of work of the clean run was reviewed")
        Instant since) {}
