package de.tum.cit.aet.hephaestus.core.auth.spi;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The messages of the account's conversations as the app shows them: what the account wrote, Hephaestus's replies and its
 * weekly digests (GDPR Art. 15(1)). Raw tool results stay out: they are where other people's data enters a
 * conversation, which Art. 15(4) protects. A reply only quotes what the account could already see. Redacted messages
 * stay out too.
 */
public interface AccountConversationExport {
    List<Message> messages(long accountId);

    /** @param author {@code USER} for the account's own words, {@code ANSWER} for a reply, {@code DIGEST} for a digest */
    record Message(
            String workspace,
            UUID conversationId,
            String surface,
            String author,
            String text,
            Instant writtenAt,
            @Nullable Instant editedAt) {}
}
