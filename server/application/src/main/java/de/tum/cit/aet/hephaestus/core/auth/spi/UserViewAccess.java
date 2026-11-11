package de.tum.cit.aet.hephaestus.core.auth.spi;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;

/** Durable attribution for read-only user views, keyed on verified provider identity. */
public interface UserViewAccess {
    void requireRecentSignIn(@Nullable Authentication authentication, long actingAccountId);

    /**
     * Must complete durably before the caller releases private information. {@code reasonHeader} is
     * the percent-encoded UTF-8 value of {@code X-User-View-Reason}; {@code read} is the request
     * path and query.
     */
    void record(long workspaceId, @Nullable Long actorId, @Nullable Long accountId, String reasonHeader, String read);
}
