package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiChoice;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** One account-wide answer; model names belong only to workspace-specific views. */
public record AccountAiChoiceDTO(
        @Nullable MemberAiChoice choice, @Nullable Instant updatedAt) {}
