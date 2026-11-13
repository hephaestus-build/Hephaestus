package de.tum.cit.aet.hephaestus.integration.core.connection;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionQueries;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.hibernate.Session;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class ConnectionQueriesIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ConnectionQueries queries;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    @Test
    void shouldReadWorkspaceMetadataAcrossStatesWithoutReadingAnotherWorkspace() {
        String suffix = UUID.randomUUID().toString();
        var mine = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("query-" + suffix));
        var other = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("foreign-" + suffix));
        var active = new Connection(
                mine,
                IntegrationKind.SLACK,
                "T-" + suffix,
                new ConnectionConfig.SlackConfig("T-" + suffix, "Engineering", null, Set.of()));
        ConnectionFixtures.reachState(active, IntegrationState.ACTIVE);
        active.setDisplayName("Engineering");
        active = connections.saveAndFlush(active);
        var paused = new Connection(
                mine,
                IntegrationKind.OUTLINE,
                "team-" + suffix,
                new ConnectionConfig.OutlineConfig("https://docs.example.test", null, null, Set.of()));
        ConnectionFixtures.reachState(paused, IntegrationState.SUSPENDED);
        paused.setDisplayName("Docs paused");
        paused = connections.saveAndFlush(paused);
        var foreign = new Connection(
                other,
                IntegrationKind.SLACK,
                "foreign-" + suffix,
                new ConnectionConfig.SlackConfig("foreign-" + suffix, "Other", null, Set.of()));
        ConnectionFixtures.reachState(foreign, IntegrationState.ACTIVE);
        foreign = connections.saveAndFlush(foreign);

        long foreignId = foreign.getId();
        var session = Objects.requireNonNull(entityManager).unwrap(Session.class);
        session.clear();
        assertThat(queries.findByWorkspaceId(mine.getId()))
                .contains(
                        new ConnectionQueries.ConnectionView(
                                active.getId(),
                                active.getKind(),
                                active.getState(),
                                active.getConfig(),
                                active.getInstanceKey(),
                                active.getDisplayName()),
                        new ConnectionQueries.ConnectionView(
                                paused.getId(),
                                paused.getKind(),
                                paused.getState(),
                                paused.getConfig(),
                                paused.getInstanceKey(),
                                paused.getDisplayName()))
                .noneMatch(row -> row.id() == foreignId);
        assertThat(queries.findActive(mine.getId(), IntegrationKind.SLACK))
                .contains(new ConnectionQueries.ConnectionSnapshot(
                        active.getId(), active.getVersion(), active.getConfig()));
        assertThat(queries.findActive(mine.getId(), IntegrationKind.OUTLINE)).isEmpty();
        assertThat(queries.findActiveOrSuspendedConfig(mine.getId(), IntegrationKind.OUTLINE))
                .contains(paused.getConfig());
        assertThat(queries.findActiveOrSuspendedConfig(other.getId(), IntegrationKind.OUTLINE))
                .isEmpty();
        assertThat(queries.hasConnectionInState(mine.getId(), IntegrationKind.OUTLINE, IntegrationState.SUSPENDED))
                .isTrue();
        assertThat(queries.hasConnectionInState(other.getId(), IntegrationKind.OUTLINE, IntegrationState.SUSPENDED))
                .isFalse();
        assertThat(session.getStatistics().getEntityCount()).isZero();
    }
}
