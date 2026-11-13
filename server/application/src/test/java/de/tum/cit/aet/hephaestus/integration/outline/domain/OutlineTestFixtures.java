package de.tum.cit.aet.hephaestus.integration.outline.domain;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionFixtures;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class OutlineTestFixtures {
    private OutlineTestFixtures() {}

    public static Connection connection(ConnectionRepository connections, Workspace workspace) {
        var connection = new Connection(
                workspace,
                IntegrationKind.OUTLINE,
                UUID.randomUUID().toString(),
                new ConnectionConfig.OutlineConfig("https://wiki.example.com", null, null, Set.of()));
        ConnectionFixtures.reachState(connection, IntegrationState.ACTIVE);
        return connections.saveAndFlush(connection);
    }
}
