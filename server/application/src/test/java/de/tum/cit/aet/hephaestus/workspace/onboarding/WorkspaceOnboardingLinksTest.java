package de.tum.cit.aet.hephaestus.workspace.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.auth.spi.LoginProviderQuery;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class WorkspaceOnboardingLinksTest extends BaseUnitTest {
    @Mock
    private ConnectionRepository connections;

    @Mock
    private LoginProviderQuery providers;

    @Mock
    private AccountIdentityQuery identities;

    @Mock
    private GitProviderRegistry registry;

    private WorkspaceOnboardingLinks links;

    @BeforeEach
    void setUp() {
        links = new WorkspaceOnboardingLinks(connections, providers, identities, registry);
    }

    private Connection slack() {
        var connection = new Connection(
                new Workspace(),
                IntegrationKind.SLACK,
                "T123",
                new ConnectionConfig.SlackConfig("T123", "Engineering", null, null, null, Set.of()));
        org.springframework.test.util.ReflectionTestUtils.setField(connection, "id", 9L);
        connection.setState(IntegrationState.ACTIVE);
        return connection;
    }

    private void identity(String type, String server, String team) {
        when(identities.activeLinksForAccount(10L))
                .thenReturn(List.of(
                        new AccountIdentityQuery.IdentityLinkView(1L, 2L, "U123", null, null, null, null, null, team)));
        when(registry.providerTypeName(2L)).thenReturn(type);
        lenient().when(registry.providerServerUrl(2L)).thenReturn(server);
    }

    @Test
    void shouldRequireTheWorkspaceSlackTeamNotAnySlackLink() {
        when(connections.findByWorkspaceId(1L)).thenReturn(List.of(slack()));
        when(providers.enabledProviders())
                .thenReturn(List.of(new LoginProviderQuery.Provider("slack", "Slack", "SLACK", "https://slack.com")));
        identity("SLACK", "https://slack.com", "OTHER");
        var result = links.options(1L, 10L, List.of(9L));
        assertThat(result).singleElement().satisfies(link -> {
            assertThat(link.required()).isTrue();
            assertThat(link.available()).isTrue();
            assertThat(link.linked()).isFalse();
        });
    }

    @Test
    void shouldRecognizeVerifiedSlackLinkWhenItsLoginProviderIsDisabled() {
        when(connections.findByWorkspaceId(1L)).thenReturn(List.of(slack()));
        identity("SLACK", "https://slack.com", "T123");
        assertThat(links.options(1L, 10L, List.of(9L))).singleElement().satisfies(link -> {
            assertThat(link.available()).isFalse();
            assertThat(link.linked()).isTrue();
        });
    }

    @Test
    void shouldNotAcceptAnOutlineLinkFromAnotherServerWithTheSameTeamId() {
        var connection = new Connection(
                new Workspace(),
                IntegrationKind.OUTLINE,
                "team",
                new ConnectionConfig.OutlineConfig("https://docs.example.org", null, null, Set.of()));
        org.springframework.test.util.ReflectionTestUtils.setField(connection, "id", 9L);
        connection.setState(IntegrationState.ACTIVE);
        when(connections.findByWorkspaceId(1L)).thenReturn(List.of(connection));
        when(providers.enabledProviders())
                .thenReturn(List.of(
                        new LoginProviderQuery.Provider("outline", "Outline", "OUTLINE", "https://docs.example.org")));
        identity("OUTLINE", "https://other.example.org", "team");
        assertThat(links.options(1L, 10L, List.of(9L))).singleElement().satisfies(link -> {
            assertThat(link.available()).isTrue();
            assertThat(link.linked()).isFalse();
        });
    }

    @Test
    void shouldKeepRemovedRequiredConnectionsVisibleInsteadOfWaivingThem() {
        assertThat(links.options(1L, 10L, List.of(9L))).singleElement().satisfies(link -> {
            assertThat(link.connectionId()).isEqualTo(9L);
            assertThat(link.required()).isTrue();
            assertThat(link.available()).isFalse();
            assertThat(link.linked()).isFalse();
        });
    }

    @Test
    void shouldRejectMalformedOriginsEvenWhenBothAreMalformed() {
        var connection = new Connection(
                new Workspace(),
                IntegrationKind.OUTLINE,
                "team",
                new ConnectionConfig.OutlineConfig("not-a-url", null, null, Set.of()));
        org.springframework.test.util.ReflectionTestUtils.setField(connection, "id", 9L);
        connection.setState(IntegrationState.ACTIVE);
        when(connections.findByWorkspaceId(1L)).thenReturn(List.of(connection));
        when(providers.enabledProviders())
                .thenReturn(List.of(new LoginProviderQuery.Provider("outline", "Outline", "OUTLINE", "not-a-url")));
        assertThat(links.options(1L, 10L, List.of(9L))).singleElement().satisfies(link -> {
            assertThat(link.available()).isFalse();
            assertThat(link.linked()).isFalse();
        });
    }
}
