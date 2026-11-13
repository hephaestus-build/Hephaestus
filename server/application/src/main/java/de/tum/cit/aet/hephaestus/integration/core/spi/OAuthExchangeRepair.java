package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Durable evidence of an external authorization exchange, independent of its local completion. */
public interface OAuthExchangeRepair {
    UUID begin(IntegrationRef owner);

    void observed(UUID attemptId, String teamId, @Nullable String botUserId, String tokenFingerprint);

    void uncertain(UUID attemptId);

    void discard(UUID attemptId);
}
