package de.tum.cit.aet.hephaestus.core.audit.spi;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A human-readable actor identity on an audit row. Resolved at read time, so an erased account
 * degrades here without the trail being rewritten: {@code AccountPurger} clears the email, replaces
 * the display name with a placeholder, and nulls the row's actor references.
 *
 * <p>Named distinctly from the auth trail's own {@code AccountRefDTO}: the OpenAPI schema key is the
 * simple name minus the {@code DTO} suffix, so two {@code AccountRefDTO} records would collide and one
 * would be silently dropped from the spec.
 */
public record ConfigAuditActorRefDTO(
        @NonNull Long id,
        @Nullable String displayName,
        @Nullable String email) {}
