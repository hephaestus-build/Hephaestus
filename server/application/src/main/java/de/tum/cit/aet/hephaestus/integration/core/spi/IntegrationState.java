package de.tum.cit.aet.hephaestus.integration.core.spi;

import de.tum.cit.aet.hephaestus.core.Lifecycle;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Per-Connection lifecycle state.
 *
 * <p>{@code PENDING} models the OAuth round-trip — we model OUR wait, not vendor's
 * setup work. {@code UNINSTALLED} is terminal for generic transitions: credentials were
 * cleared and the integration is unusable. Kind-specific reconnect flows may opt into a
 * guarded revival in their application service when that preserves the vendor natural key.
 *
 * <p>Idempotent transitions: hitting {@code SUSPENDED} on an already-{@code SUSPENDED}
 * Connection returns the current state with no audit row — protects against webhook
 * redelivery floods.
 */
public enum IntegrationState implements Lifecycle<IntegrationState> {
    /** OAuth in flight, or pending vendor-side accept. */
    PENDING,

    /** Credentials valid; the integration is usable. */
    ACTIVE,

    /** Vendor revoked / Hephaestus admin paused; credentials retained but unused. */
    SUSPENDED,

    /** Hard disconnected; credentials cleared. Terminal — no legal transition out. */
    UNINSTALLED;

    private static final Map<IntegrationState, Set<IntegrationState>> LEGAL = Map.of(
            PENDING,
            Set.of(ACTIVE, UNINSTALLED),
            ACTIVE,
            Set.of(SUSPENDED, UNINSTALLED),
            SUSPENDED,
            Set.of(ACTIVE, UNINSTALLED),
            UNINSTALLED,
            Set.of());

    @Override
    public boolean canTransitionTo(IntegrationState next) {
        return Objects.requireNonNull(LEGAL.get(this)).contains(next);
    }
}
