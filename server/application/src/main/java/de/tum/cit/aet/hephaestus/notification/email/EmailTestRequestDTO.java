package de.tum.cit.aet.hephaestus.notification.email;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * @param to the mailbox to send the test email to; blank means the caller's own verified address
 */
public record EmailTestRequestDTO(
        @Nullable @Email @Size(max = 254) String to) {}
