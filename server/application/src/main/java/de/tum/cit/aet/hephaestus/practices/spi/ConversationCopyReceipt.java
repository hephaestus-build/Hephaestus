package de.tum.cit.aet.hephaestus.practices.spi;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * How a claimed conversation copy ended.
 *
 * @param conversationTurnId the Hephaestus turn that claimed the copy
 * @param conversationEntryId the Hephaestus answer that showed the words; required for {@code DELIVERED}
 */
public record ConversationCopyReceipt(
        UUID conversationTurnId,
        @Nullable UUID conversationEntryId,
        ConversationCopyOutcome outcome,
        Instant at,
        @Nullable String reason) {}
