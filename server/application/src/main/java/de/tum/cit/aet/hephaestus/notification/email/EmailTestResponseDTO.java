package de.tum.cit.aet.hephaestus.notification.email;

import de.tum.cit.aet.hephaestus.notification.email.EmailDeliveryResult.Outcome;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * @param outcome what became of the test email; {@code SENT} is the only success
 * @param to the address it went to, or {@code null} when there was no recipient to resolve
 * @param messageId the accepted {@code Message-ID}, only for {@code SENT}; search for it in the relay's log
 */
public record EmailTestResponseDTO(
        @NonNull Outcome outcome,
        @Nullable String to,
        @Nullable String messageId) {}
