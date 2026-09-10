package de.tum.cit.aet.hephaestus.integration.core.email;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Spring Boot owns SMTP transport configuration; this is only the sender identity. */
@Validated
@ConfigurationProperties("hephaestus.email")
public record SmtpEmailProperties(
        @Nullable @Email @Size(max = 320) String from) {}
