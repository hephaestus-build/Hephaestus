package de.tum.cit.aet.hephaestus.integration.core.connection.repair;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.AuthPropertiesFixture;
import de.tum.cit.aet.hephaestus.core.web.PublicApiUrls;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter.Removal;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter.Resource;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter.ResourceType;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineWebhookClient;
import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineWebhookSubscription;
import de.tum.cit.aet.hephaestus.integration.outline.lifecycle.OutlineResourceAdapter;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.github.connect.GitHubResourceAdapter;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient.WebhookInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.connect.GitLabResourceAdapter;
import de.tum.cit.aet.hephaestus.integration.slack.connect.SlackOAuthClient;
import de.tum.cit.aet.hephaestus.integration.slack.connect.SlackResourceAdapter;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

@Tag("unit")
class ProviderResourceAdaptersTest {
    private static final PublicApiUrls URLS =
            new PublicApiUrls(AuthPropertiesFixture.withApiBase(URI.create("https://receiver.example.test"), "/api"));
    private static final BearerToken TOKEN = new BearerToken("test-provider-token", null);

    @Test
    void githubTreatsAcceptanceAsUncertainAndKeepsTheCapturedAppIdentity() {
        GitHubAppTokenService app = mock(GitHubAppTokenService.class);
        when(app.getConfiguredAppId()).thenReturn(77L);
        when(app.isConfigured()).thenReturn(true);
        var adapter = new GitHubResourceAdapter(app);
        var config = new ConnectionConfig.GitHubAppConfig(42L, 77L, "org", "https://github.com", Set.of());
        Resource target = adapter.resources(new IntegrationRef(IntegrationKind.GITHUB, 1, "42"), config, null)
                .getFirst();
        assertThat(adapter.remove(target, null, () -> true)).isEqualTo(Removal.AWAITING_CONFIRMATION);
        verify(app).installation(42L);
        verify(app).deleteInstallation(42L);
        when(app.getConfiguredAppId()).thenReturn(88L);
        assertThat(adapter.remove(target, null, () -> true)).isEqualTo(Removal.NEEDS_AUTHORIZATION);
        assertThat(target.parentId()).isEqualTo("77");
        assertThat(adapter.resources(
                        ref(IntegrationKind.GITHUB),
                        new ConnectionConfig.GitHubPatConfig("org", null, Set.of()),
                        TOKEN))
                .isEmpty();
    }

    @Test
    void githubNeverDeletesAfterAnUnreadableInstallationOrARevokedDispatchPermit() {
        GitHubAppTokenService app = mock(GitHubAppTokenService.class);
        when(app.getConfiguredAppId()).thenReturn(77L);
        when(app.isConfigured()).thenReturn(true);
        Resource target =
                new Resource(IntegrationKind.GITHUB, "https://github.com", ResourceType.INSTALLATION, "77", "42", null);
        var adapter = new GitHubResourceAdapter(app);
        assertThat(adapter.remove(target, null, () -> false)).isEqualTo(Removal.CANCELLED);
        doThrow(new IllegalStateException("permission-hidden 404")).when(app).installation(42L);
        assertThatThrownBy(() -> adapter.remove(target, null, () -> true)).isInstanceOf(IllegalStateException.class);
        verify(app, never()).deleteInstallation(anyLong());
    }

    @Test
    void gitlabReadsTheCapturedHookAndDoesNotDeleteAForeignReusedId() {
        GitLabWebhookClient client = mock(GitLabWebhookClient.class);
        var beans = new StaticListableBeanFactory();
        beans.addBean("client", client);
        var adapter = new GitLabResourceAdapter(beans.getBeanProvider(GitLabWebhookClient.class), URLS);
        String oldUrl = "https://receiver.example.test/api/hooks/old?hephaestus_resource=old-marker";
        Resource target = new Resource(
                IntegrationKind.GITLAB,
                "https://gitlab.example.test",
                ResourceType.GROUP_HOOK,
                "99",
                "42",
                ProviderResourceAdapter.fingerprint(oldUrl));
        when(client.listGroupWebhooksWithCredentials(target.origin(), TOKEN.token(), 99L))
                .thenReturn(List.of(new WebhookInfo(42L, "https://foreign.example.test/hook")));
        assertThat(adapter.remove(target, TOKEN, () -> true)).isEqualTo(Removal.DIFFERENT_OWNER);
        verify(client, never()).deregisterGroupWebhookWithCredentials(anyString(), anyString(), anyLong(), anyLong());
        when(client.listGroupWebhooksWithCredentials(target.origin(), TOKEN.token(), 99L))
                .thenReturn(List.of(new WebhookInfo(42L, oldUrl)));
        when(client.deregisterGroupWebhookWithCredentials(target.origin(), TOKEN.token(), 99L, 42L))
                .thenReturn(true);
        assertThat(adapter.remove(target, TOKEN, () -> true)).isEqualTo(Removal.TERMINAL_SUCCESS);
    }

    @Test
    void gitlabLateDeletionTouchesOnlyItsCapturedHook() {
        GitLabWebhookClient client = mock(GitLabWebhookClient.class);
        var beans = new StaticListableBeanFactory();
        beans.addBean("client", client);
        var adapter = new GitLabResourceAdapter(beans.getBeanProvider(GitLabWebhookClient.class), URLS);
        Map<Long, String> upstream = new ConcurrentHashMap<>(
                Map.of(42L, "https://receiver.example.test/old", 43L, "https://receiver.example.test/fresh"));
        List<Long> accepted = new ArrayList<>();
        when(client.listGroupWebhooksWithCredentials(anyString(), anyString(), anyLong()))
                .thenAnswer(call -> upstream.entrySet().stream()
                        .map(entry -> new WebhookInfo(entry.getKey(), entry.getValue()))
                        .toList());
        when(client.deregisterGroupWebhookWithCredentials(anyString(), anyString(), anyLong(), anyLong()))
                .thenAnswer(call -> {
                    accepted.add(call.getArgument(3));
                    throw new IllegalStateException("local timeout after acceptance");
                });
        Resource target = new Resource(
                IntegrationKind.GITLAB,
                "https://gitlab.example.test",
                ResourceType.GROUP_HOOK,
                "99",
                "42",
                ProviderResourceAdapter.fingerprint("https://receiver.example.test/old"));
        assertThatThrownBy(() -> adapter.remove(target, TOKEN, () -> true)).isInstanceOf(IllegalStateException.class);
        accepted.forEach(upstream::remove);
        assertThat(upstream).containsOnlyKeys(43L);
    }

    @Test
    void outlineChecksTheSubscriptionUrlAndRequiresATerminalReply() {
        OutlineWebhookClient client = mock(OutlineWebhookClient.class);
        var adapter = new OutlineResourceAdapter(
                new StaticListableBeanFactory(Map.of("outline", client)).getBeanProvider(OutlineWebhookClient.class),
                URLS);
        String oldUrl = "https://receiver.example.test/old";
        Resource target = new Resource(
                IntegrationKind.OUTLINE,
                "https://outline.example.test",
                ResourceType.SUBSCRIPTION,
                "",
                "old",
                ProviderResourceAdapter.fingerprint(oldUrl));
        when(client.listWebhookSubscriptions(target.origin(), TOKEN.token()))
                .thenReturn(
                        List.of(new OutlineWebhookSubscription().id("old").url("https://foreign.example.test/hook")));
        assertThat(adapter.remove(target, TOKEN, () -> true)).isEqualTo(Removal.DIFFERENT_OWNER);
        verify(client, never()).deleteWebhookSubscription(anyString(), anyString(), anyString());
        when(client.listWebhookSubscriptions(target.origin(), TOKEN.token()))
                .thenReturn(List.of(new OutlineWebhookSubscription().id("old").url(oldUrl)));
        when(client.deleteWebhookSubscription(target.origin(), TOKEN.token(), "old"))
                .thenReturn(false, true);
        assertThat(adapter.remove(target, TOKEN, () -> true)).isEqualTo(Removal.AWAITING_CONFIRMATION);
        assertThat(adapter.remove(target, TOKEN, () -> true)).isEqualTo(Removal.TERMINAL_SUCCESS);
    }

    @Test
    void outlineKeepsItsIdentityCapturableWhenTheProviderClientIsDisabled() {
        var adapter = new OutlineResourceAdapter(
                new StaticListableBeanFactory().getBeanProvider(OutlineWebhookClient.class), URLS);
        var resources = adapter.resources(
                new IntegrationRef(IntegrationKind.OUTLINE, 1, "team", 7L),
                new ConnectionConfig.OutlineConfig("https://outline.example.test", "old", "key/route", Set.of()),
                null);
        assertThat(resources).singleElement().satisfies(resource -> {
            assertThat(resource.resourceId()).isEqualTo("old");
            assertThat(adapter.remove(resource, TOKEN, () -> true)).isEqualTo(Removal.NEEDS_AUTHORIZATION);
        });
    }

    @Test
    void outlineDiscoveryMatchesBothTheAttemptMarkerAndItsImmutableUrl() {
        OutlineWebhookClient client = mock(OutlineWebhookClient.class);
        var adapter = new OutlineResourceAdapter(
                new StaticListableBeanFactory(Map.of("outline", client)).getBeanProvider(OutlineWebhookClient.class),
                URLS);
        String url = "https://receiver.example.test/old";
        Resource registration = new Resource(
                IntegrationKind.OUTLINE,
                "https://outline.example.test",
                ResourceType.REGISTRATION,
                "",
                "marker-1",
                ProviderResourceAdapter.fingerprint(url));
        when(client.listWebhookSubscriptions(registration.origin(), TOKEN.token()))
                .thenReturn(List.of(
                        new OutlineWebhookSubscription()
                                .id("owned")
                                .name("Hephaestus documentation sync [marker-1]")
                                .url(url),
                        new OutlineWebhookSubscription()
                                .id("fresh")
                                .name("Hephaestus documentation sync [marker-2]")
                                .url("https://receiver.example.test/fresh"),
                        new OutlineWebhookSubscription()
                                .id("foreign")
                                .name("Hephaestus documentation sync [marker-1]")
                                .url("https://foreign.example.test/hook")));
        assertThat(adapter.discoverRegistration(registration, TOKEN))
                .extracting(Resource::resourceId)
                .containsExactly("owned");
    }

    @Test
    void slackPersistsBothTheTokenAndBotUserWithoutRetainingTheToken() {
        SlackOAuthClient client = mock(SlackOAuthClient.class);
        var adapter = new SlackResourceAdapter(client);
        String fingerprint = ProviderResourceAdapter.slackTokenFingerprint(TOKEN.token());
        var config = new ConnectionConfig.SlackConfig("T1", "U1", fingerprint, "Team", null, Set.of());
        List<Resource> identities = adapter.resources(ref(IntegrationKind.SLACK), config, TOKEN);
        assertThat(identities)
                .extracting(Resource::type)
                .containsExactly(ResourceType.BOT_USER, ResourceType.BOT_TOKEN);
        assertThat(adapter.resources(ref(IntegrationKind.SLACK), config, null)).isEqualTo(identities);
        assertThat(identities.toString()).doesNotContain(TOKEN.token());
        when(client.botIdentity(TOKEN.token())).thenReturn(new SlackOAuthClient.BotIdentity(true, "T1", "U1", null));
        assertThat(adapter.remove(identities.get(1), TOKEN, () -> true)).isEqualTo(Removal.TERMINAL_SUCCESS);
        assertThat(adapter.remove(identities.getFirst(), TOKEN, () -> true)).isEqualTo(Removal.ADMIN_CONFIRMATION);
    }

    @Test
    void slackCannotRevokeAReplacementTokenUsingTheOldRemovalIdentity() {
        SlackOAuthClient client = mock(SlackOAuthClient.class);
        var adapter = new SlackResourceAdapter(client);
        Resource old = new Resource(
                IntegrationKind.SLACK,
                "https://slack.com",
                ResourceType.BOT_TOKEN,
                "T1/U1",
                ProviderResourceAdapter.slackTokenFingerprint("old-token"),
                null);
        assertThat(adapter.remove(old, new BearerToken("replacement-token", null), () -> true))
                .isEqualTo(Removal.NEEDS_AUTHORIZATION);
        verify(client, never()).revokeStrict(anyString());
    }

    private static IntegrationRef ref(IntegrationKind kind) {
        return new IntegrationRef(kind, 1L, "instance", 2L);
    }
}
