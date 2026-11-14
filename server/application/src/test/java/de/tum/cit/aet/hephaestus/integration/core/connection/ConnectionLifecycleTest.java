package de.tum.cit.aet.hephaestus.integration.core.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ConnectionLifecycleTest extends BaseUnitTest {
    private static final Set<String> EDGES = Set.of(
            "PENDING/ACTIVE",
            "PENDING/UNINSTALLED",
            "ACTIVE/SUSPENDED",
            "ACTIVE/UNINSTALLED",
            "SUSPENDED/ACTIVE",
            "SUSPENDED/UNINSTALLED");
    private static final List<String> EVENTS = List.of("INITIATE", "OAUTH_COMPLETE", "UNVERIFIED");

    private static Stream<Arguments> transitions() {
        return Stream.of(IntegrationKind.values())
                .flatMap(kind -> Stream.of(IntegrationState.values())
                        .flatMap(from -> Stream.of(IntegrationState.values())
                                .flatMap(to -> EVENTS.stream().map(event -> Arguments.of(kind, from, to, event)))));
    }

    @ParameterizedTest
    @MethodSource("transitions")
    void shouldApplyOnlyLegalAndAuthorizedTransitions(
            IntegrationKind kind, IntegrationState from, IntegrationState to, String event) {
        var connection = connection(kind);
        ConnectionFixtures.reachState(connection, from);
        assertStep(connection, to, event);
    }

    @Test
    void shouldPreserveIdentityAndIncrementOnlyOnAStateChangeAcrossSeededEventWalks() {
        for (var kind : IntegrationKind.values()) {
            var connection = connection(kind);
            var random = new Random(0x4245L + kind.name().hashCode());
            for (int step = 0; step < 300; step++) {
                var states = List.of(IntegrationState.values());
                var next = states.get(random.nextInt(states.size()));
                assertStep(connection, next, EVENTS.get(random.nextInt(EVENTS.size())));
            }
        }
    }

    private static void assertStep(Connection connection, IntegrationState next, String event) {
        var previous = connection.getState();
        var reason = connection.getStateReason();
        long generation = connection.getLifecycleGeneration();
        var config = connection.getConfig();
        boolean reconnect = previous == IntegrationState.UNINSTALLED
                && next == IntegrationState.ACTIVE
                && (event.equals("INITIATE")
                        || (event.equals("OAUTH_COMPLETE")
                                && (connection.getKind() == IntegrationKind.GITHUB
                                        || connection.getKind() == IntegrationKind.SLACK)));
        boolean legal = previous == next || EDGES.contains(previous + "/" + next) || reconnect;
        if (legal) {
            connection.transitionTo(next, event, "next reason");
            assertThat(connection.getState()).isEqualTo(next);
            assertThat(connection.getStateReason()).isEqualTo(previous == next ? reason : "next reason");
            assertThat(connection.getLifecycleGeneration()).isEqualTo(generation + (previous == next ? 0 : 1));
        } else {
            assertThatThrownBy(() -> connection.transitionTo(next, event, "invalid reason"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cannot change from");
            assertThat(connection.getState()).isEqualTo(previous);
            assertThat(connection.getStateReason()).isEqualTo(reason);
            assertThat(connection.getLifecycleGeneration()).isEqualTo(generation);
        }
        assertThat(connection.getConfig()).isSameAs(config);
    }

    private static Connection connection(IntegrationKind kind) {
        ConnectionConfig config = switch (kind) {
            case GITHUB -> new ConnectionConfig.GitHubPatConfig("course", null, Set.of());
            case GITLAB -> new ConnectionConfig.GitLabConfig("https://gitlab.com", 42L, null, Set.of(), null);
            case SLACK -> new ConnectionConfig.SlackConfig("T1", "Course", null, Set.of());
            case OUTLINE -> new ConnectionConfig.OutlineConfig("https://outline.example.test", null, null, Set.of());
        };
        return new Connection(new Workspace(), kind, "identity", config);
    }
}
