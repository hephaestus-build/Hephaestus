package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiChoice;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** The signed-in account's AI choice; both fields are absent until the person has answered. */
public record AccountAiChoiceDTO(
        @Nullable MemberAiChoice choice, @Nullable Instant updatedAt) {}
