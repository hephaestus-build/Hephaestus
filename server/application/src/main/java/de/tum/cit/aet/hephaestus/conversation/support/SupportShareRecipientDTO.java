package de.tum.cit.aet.hephaestus.conversation.support;

import org.jspecify.annotations.NonNull;

/** An instance admin who can receive a support share. */
public record SupportShareRecipientDTO(
        @NonNull String accountId, @NonNull String displayName) {}
