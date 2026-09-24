package de.tum.cit.aet.hephaestus.core.auth.spi;

import java.util.Collection;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Durable attribution for read-only user views, keyed on verified provider identity. */
public interface UserViewAccess {
    Map<Long, LinkedAccount> linkedAccounts(Collection<ActorIdentity> actors);

    /**
     * Must complete durably before the caller releases private information. {@code reasonHeader} is the
     * percent-encoded UTF-8 value of {@code X-User-View-Reason}; {@code read} is the request path and query.
     */
    void record(long workspaceId, long userId, @Nullable Long accountId, String reasonHeader, String read);

    record ActorIdentity(long actorId, long providerId, String subject) {}

    record LinkedAccount(long accountId, String status) {}
}
