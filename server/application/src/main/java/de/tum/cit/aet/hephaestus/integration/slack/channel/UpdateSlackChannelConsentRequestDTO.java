package de.tum.cit.aet.hephaestus.integration.slack.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Request to transition a Slack channel to a target consent state. The transition is a guarded, idempotent switch:
 * Activation enters {@code ACTIVATING}; only a confirmed announcement enables ingestion.
 * {@code ACTIVE ⇄ PAUSED} (stop/resume, data kept), {@code * → REVOKED} (stop + erase). An illegal edge is a 409.
 */
@Schema(description = "Transition a Slack channel to a target consent state")
public record UpdateSlackChannelConsentRequestDTO(
        @NonNull @NotNull @Schema(description = "Target consent state", requiredMode = Schema.RequiredMode.REQUIRED)
        SlackChannelConsentTarget consentState,

        @Size(max = 2000)
        @Schema(description = "Optional free-text reason recorded in the immutable audit trail")
        @Nullable
        String reason) {}
