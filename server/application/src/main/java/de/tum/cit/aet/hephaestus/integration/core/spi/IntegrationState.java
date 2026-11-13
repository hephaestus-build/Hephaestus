package de.tum.cit.aet.hephaestus.integration.core.spi;

import de.tum.cit.aet.hephaestus.core.ContinuingLifecycle;
import de.tum.cit.aet.hephaestus.core.Lifecycle;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Per-connection lifecycle. Reconnect reuses the row; the aggregate guards the verified reconnect authority. */
@ContinuingLifecycle(
        "A reconnect reuses the connection identity and advances its lifecycle generation; disconnect is not terminal.")
public enum IntegrationState implements Lifecycle<IntegrationState> {
    /** OAuth in flight, or pending vendor-side accept. */
    PENDING,
    /** Credentials valid; the integration is usable. */
    ACTIVE,
    /** Vendor revoked / Hephaestus admin paused; credentials retained but unused. */
    SUSPENDED,
    /** Disconnected; a verified reconnect can start a new generation of this connection. */
    UNINSTALLED;

    public enum Transition {
        CONNECT,
        SUSPEND,
        RESUME,
        DISCONNECT,
        RECONNECT
    }

    private static final Map<IntegrationState, Map<IntegrationState, Transition>> LEGAL = Map.of(
            PENDING, Map.of(ACTIVE, Transition.CONNECT, UNINSTALLED, Transition.DISCONNECT),
            ACTIVE, Map.of(SUSPENDED, Transition.SUSPEND, UNINSTALLED, Transition.DISCONNECT),
            SUSPENDED, Map.of(ACTIVE, Transition.RESUME, UNINSTALLED, Transition.DISCONNECT),
            UNINSTALLED, Map.of(ACTIVE, Transition.RECONNECT));

    public Optional<Transition> transitionTo(IntegrationState next) {
        return Optional.ofNullable(Objects.requireNonNull(LEGAL.get(this)).get(Objects.requireNonNull(next)));
    }

    @Override
    public boolean canTransitionTo(IntegrationState next) {
        return transitionTo(next).isPresent();
    }
}
