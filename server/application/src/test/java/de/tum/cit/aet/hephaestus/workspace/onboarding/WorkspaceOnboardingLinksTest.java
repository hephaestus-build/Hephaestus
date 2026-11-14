package de.tum.cit.aet.hephaestus.workspace.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.auth.spi.LoginProviderQuery;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionQueries;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionQueries.ConnectionView;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class WorkspaceOnboardingLinksTest extends BaseUnitTest {
    @Mock
    private ConnectionQueries connections;

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

    private ConnectionView slack() {
        var connection = new ConnectionView(
                9L,
                IntegrationKind.SLACK,
                IntegrationState.ACTIVE,
                new ConnectionConfig.SlackConfig("T123", "Engineering", null, Set.of()),
                "T123",
                null);
        return connection;
    }

    private void identity(String type, String server, String team) {
        when(identities.activeLinksForAccount(10L))
                .thenReturn(List.of(new AccountIdentityQuery.IdentityView(1L, 2L, "U123", team == null ? "" : team)));
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
        var connection = new ConnectionView(
                9L,
                IntegrationKind.OUTLINE,
                IntegrationState.ACTIVE,
                new ConnectionConfig.OutlineConfig("https://docs.example.org", null, null, Set.of()),
                "team",
                null);
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
        var connection = new ConnectionView(
                9L,
                IntegrationKind.OUTLINE,
                IntegrationState.ACTIVE,
                new ConnectionConfig.OutlineConfig("not-a-url", null, null, Set.of()),
                "team",
                null);
        when(connections.findByWorkspaceId(1L)).thenReturn(List.of(connection));
        when(providers.enabledProviders())
                .thenReturn(List.of(new LoginProviderQuery.Provider("outline", "Outline", "OUTLINE", "not-a-url")));
        assertThat(links.options(1L, 10L, List.of(9L))).singleElement().satisfies(link -> {
            assertThat(link.available()).isFalse();
            assertThat(link.linked()).isFalse();
        });
    }
}
