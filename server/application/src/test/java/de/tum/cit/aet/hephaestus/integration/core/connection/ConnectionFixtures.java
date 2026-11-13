package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import org.jspecify.annotations.Nullable;

/** Test setup follows the same legal transitions as the aggregate. It cannot reset an existing lifecycle to PENDING. */
public final class ConnectionFixtures {
    private ConnectionFixtures() {}

    public static void reachState(Connection connection, IntegrationState target) {
        reachState(connection, target, null);
    }

    public static void reachState(Connection connection, IntegrationState target, @Nullable String reason) {
        if (target == IntegrationState.SUSPENDED && connection.getState() != IntegrationState.ACTIVE)
            connection.transitionTo(IntegrationState.ACTIVE, "INITIATE", null);
        connection.transitionTo(target, "INITIATE", reason);
    }
}
