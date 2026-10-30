package de.tum.cit.aet.hephaestus.agent.mentor.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "A question to Hephaestus from the app")
public record MentorTurnRequestDTO(
        @NotNull
        @Schema(
                description = "Chosen by the app for each message. Sending the same id again returns the first "
                        + "submission, so a retried request never asks twice.")
        @NonNull
        UUID requestId,

        @NotBlank @Schema(description = "The developer's words") @NonNull
        String text,

        @Schema(description = "Ask in the thread on this top-level message; the first reply opens the thread") @Nullable
        UUID parentEntryId,

        @Size(max = 500)
        @Schema(description = "The page the developer asks from, for example \"Pull request #421 in acme/web\"")
        @Nullable
        String pageContext) {}
