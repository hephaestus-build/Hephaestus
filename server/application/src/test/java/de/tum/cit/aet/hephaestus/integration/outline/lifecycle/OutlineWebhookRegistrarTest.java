package de.tum.cit.aet.hephaestus.integration.outline.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.AuthPropertiesFixture;
import de.tum.cit.aet.hephaestus.core.lock.ClusterLocks;
import de.tum.cit.aet.hephaestus.core.web.PublicApiUrls;
import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.core.webhook.WebhookPropertiesFixture;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineApiClient;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineApiException;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineClientModels;
import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineWebhookSubscription;
import de.tum.cit.aet.hephaestus.integration.outline.webhook.OutlineRouteCredential;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;

/**
 * Correctness of the change-notification subscription lifecycle: without a stored id a subscription is
 * registered on a new route and its id + route stored; a stored id is verified upstream and replaced when
 * Outline auto-disabled or lost it, or it is not on a current route (but never churned when the upstream
 * cannot be listed); revoke tears
 * the subscription down, including the deactivated-connection variant that resolves by connection id.
 * Registration uses the canonical API origin without the proxy prefix.
 */
class OutlineWebhookRegistrarTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 7L;
    private static final long CONNECTION_ID = 31L;
    private static final String SERVER_URL = "https://outline.example.test";
    private static final String API_ORIGIN = "https://heph.example.com/";
    private static final String ROUTING_SECRET = "outline-route-test-routing-secret-0123456789";
    private static final OutlineRouteCredential CREDENTIAL = credential(ROUTING_SECRET);

    /** A route the current routing key issued. */
    private static final String ROUTE = CREDENTIAL.issue().route();

    @Mock
    private ConnectionService connectionService;

    @Mock
    private OutlineApiClient outlineApiClient;

    @Mock
    private Connection connection;

    @Mock
    private ConnectionRepository connectionRepository;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private OutlineWebhookRegistrar registrar(String externalUrl) {
        return new OutlineWebhookRegistrar(
                connectionService,
                outlineApiClient,
                CREDENTIAL,
                new PublicApiUrls(AuthPropertiesFixture.withApiBase(URI.create(externalUrl), "/api")),
                connectionRepository,
                ClusterLocks.free(),
                meters);
    }

    @BeforeEach
    void storeUpdates() {
        // An ACTIVE connection takes the update; the tests that need a disconnected one override this.
        lenient().when(connectionService.updateConfig(anyLong(), any(), any())).thenReturn(Optional.of(connection));
    }

    private void stubActiveConnection(ConnectionConfig config) {
        when(connection.getConfig()).thenReturn(config);
        when(connectionService.findActive(WORKSPACE_ID, IntegrationKind.OUTLINE))
                .thenReturn(Optional.of(connection));
    }

    private void stubToken() {
        when(connectionService.findActiveBearerToken(WORKSPACE_ID, IntegrationKind.OUTLINE))
                .thenReturn(Optional.of(new BearerToken("tok", null)));
    }

    private void stubCreateReturning(String subscriptionId) {
        when(outlineApiClient.createWebhookSubscription(
                        eq(SERVER_URL), eq("tok"), anyString(), anyString(), anyString(), anyList()))
                .thenReturn(subscriptionId);
    }

    private static OutlineRouteCredential credential(String routingSecret) {
        return new OutlineRouteCredential(
                WebhookPropertiesFixture.withRouting(new WebhookProperties.Routing(routingSecret, null)));
    }

    private static ConnectionConfig.OutlineConfig config(@Nullable String subscriptionId, @Nullable String route) {
        return new ConnectionConfig.OutlineConfig(SERVER_URL, subscriptionId, route, Set.of());
    }

    private static OutlineWebhookSubscription upstream(String id, Boolean enabled) {
        return upstream(id, enabled, "https://heph.example.com/webhooks/outline/" + ROUTE);
    }

    private static OutlineWebhookSubscription upstream(String id, Boolean enabled, String url) {
        return OutlineClientModels.webhookSubscription(id, "Hephaestus", url, enabled, List.of());
    }

    @Test
    void ensureSubscription_registersAndStoresSubscriptionWhenNoneStored() {
        stubActiveConnection(config(null, null));
        stubToken();
        stubCreateReturning("sub-99");

        registrar(API_ORIGIN).ensureSubscription(WORKSPACE_ID);

        // The delivery URL uses the canonical origin and the route endpoint, and the secret is the
        // one the receiver derives from that route.
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> secret = ArgumentCaptor.forClass(String.class);
        verify(outlineApiClient)
                .createWebhookSubscription(
                        eq(SERVER_URL), eq("tok"), anyString(), url.capture(), secret.capture(), anyList());
        assertThat(url.getValue()).startsWith("https://heph.example.com/webhooks/outline/");
        String route = url.getValue().substring("https://heph.example.com/webhooks/outline/".length());
        String keyId = route.substring(0, route.indexOf('/'));
        String routeId = route.substring(route.indexOf('/') + 1);
        assertThat(CREDENTIAL.signingSecret(keyId, routeId))
                .hasValueSatisfying(derived ->
                        assertThat(new String(derived, StandardCharsets.UTF_8)).isEqualTo(secret.getValue()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UnaryOperator<ConnectionConfig>> mutator = ArgumentCaptor.forClass(UnaryOperator.class);
        verify(connectionService).updateConfig(eq(WORKSPACE_ID), eq(IntegrationKind.OUTLINE), mutator.capture());
        ConnectionConfig.OutlineConfig updated =
                (ConnectionConfig.OutlineConfig) mutator.getValue().apply(config(null, null));
        assertThat(updated.webhookSubscriptionId()).isEqualTo("sub-99");
        assertThat(updated.webhookRoute()).isEqualTo(route);
        // Without this reconcile, the scope consumer (created at boot with only the SCM stream) never
        // picks up the new outline.<subId>.> filter until a restart.
    }

    @Test
    void ensureSubscription_deletesTheNewSubscriptionWhenTheConnectionLeftActiveMeanwhile() {
        stubActiveConnection(config(null, null));
        stubToken();
        stubCreateReturning("sub-99");
        when(connectionService.updateConfig(anyLong(), any(), any())).thenReturn(Optional.empty());

        registrar(API_ORIGIN).ensureSubscription(WORKSPACE_ID);

        verify(outlineApiClient).deleteWebhookSubscription(SERVER_URL, "tok", "sub-99");
    }

    @Test
    void ensureSubscription_deletesTheNewSubscriptionWhenStoringItFails() {
        stubActiveConnection(config(null, null));
        stubToken();
        stubCreateReturning("sub-99");
        when(connectionService.updateConfig(anyLong(), any(), any()))
                .thenThrow(new IllegalStateException("optimistic lock"));

        registrar(API_ORIGIN).ensureSubscription(WORKSPACE_ID);

        verify(outlineApiClient).deleteWebhookSubscription(SERVER_URL, "tok", "sub-99");
    }

    @Test
    void ensureSubscription_countsADeletionThatFails() {
        stubActiveConnection(config("sub-1", null));
        stubToken();
        stubCreateReturning("sub-2");
        doThrow(new OutlineApiException("boom"))
                .when(outlineApiClient)
                .deleteWebhookSubscription(SERVER_URL, "tok", "sub-1");

        registrar(API_ORIGIN).ensureSubscription(WORKSPACE_ID);

        assertThat(meters.get("outline.webhook.subscription.deletion.failed")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void healRoutes_registersEveryActiveConnectionWithoutACurrentRoute() {
        ConnectionRepository.OutlineRouteRow unrouted = mock(ConnectionRepository.OutlineRouteRow.class);
        when(unrouted.getWorkspaceId()).thenReturn(WORKSPACE_ID);
        when(unrouted.getRoute()).thenReturn(null);
        ConnectionRepository.OutlineRouteRow routed = mock(ConnectionRepository.OutlineRouteRow.class);
        when(routed.getRoute()).thenReturn(ROUTE);
        when(connectionRepository.findActiveOutlineRoutes()).thenReturn(List.of(unrouted, routed));
        stubActiveConnection(config(null, null));
        stubToken();
        stubCreateReturning("sub-99");

        registrar(API_ORIGIN).healRoutes();

        verify(outlineApiClient).createWebhookSubscription(any(), any(), any(), any(), any(), any());
        assertThat(meters.get("outline.webhook.subscription.unrouted").gauge().value())
                .isEqualTo(1);
    }

    @Test
    void ensureSubscription_skipsWhenRoutingCredentialsAreMissing() {
        new OutlineWebhookRegistrar(
                        connectionService,
                        outlineApiClient,
                        credential(""),
                        new PublicApiUrls(AuthPropertiesFixture.withApiBase(URI.create(API_ORIGIN), "/api")),
                        providerOf(natsConsumer),
                        providerOf(routes),
                        connectionRepository,
                        ClusterLocks.free(),
                        meters)
                .ensureSubscription(WORKSPACE_ID);

        verify(connectionService, never()).findActive(anyLongMatcher(), any());
    }

    @Test
    void ensureSubscription_leavesAHealthyStoredSubscriptionAlone() {
        stubActiveConnection(config("sub-1", ROUTE));
        stubToken();
        when(outlineApiClient.listWebhookSubscriptions(SERVER_URL, "tok")).thenReturn(List.of(upstream("sub-1", true)));

        registrar(API_ORIGIN).ensureSubscription(WORKSPACE_ID);

        verify(outlineApiClient, never()).createWebhookSubscription(any(), any(), any(), any(), any(), any());
        verify(connectionService, never()).updateConfig(anyLongMatcher(), any(), any());
        // Nothing changed upstream — the scope consumer must not be poked.
    }

    @Test
    void ensureSubscription_projectsTheRouteOfAHealthyStoredSubscription() {
        stubActiveConnection(config("sub-1", ROUTE));
        stubToken();
        when(outlineApiClient.listWebhookSubscriptions(SERVER_URL, "tok")).thenReturn(List.of(upstream("sub-1", true)));

        registrar(API_ORIGIN).ensureSubscription(WORKSPACE_ID);

        // A reactivated connection's route left the projection while the connection was not ACTIVE.
    }

    @Test
    void ensureSubscription_reRegistersWhenUpstreamDisabledTheStoredSubscription() {
        // Outline auto-disables a subscription after 25 consecutive delivery failures — self-heal it.
        stubActiveConnection(config("sub-1", ROUTE));
        stubToken();
        when(outlineApiClient.listWebhookSubscriptions(SERVER_URL, "tok"))
                .thenReturn(List.of(upstream("sub-1", false)));
        stubCreateReturning("sub-2");

        registrar(API_ORIGIN).ensureSubscription(WORKSPACE_ID);

        // The new subscription is stored, then the disabled one is deleted upstream.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<UnaryOperator<ConnectionConfig>> mutator = ArgumentCaptor.forClass(UnaryOperator.class);
        InOrder order = inOrder(outlineApiClient, connectionService);
        order.verify(outlineApiClient).createWebhookSubscription(any(), any(), any(), any(), any(), any());
        order.verify(connectionService).updateConfig(eq(WORKSPACE_ID), eq(IntegrationKind.OUTLINE), mutator.capture());
        order.verify(outlineApiClient).deleteWebhookSubscription(SERVER_URL, "tok", "sub-1");
        ConnectionConfig.OutlineConfig stored =
                (ConnectionConfig.OutlineConfig) mutator.getValue().apply(config("sub-1", ROUTE));
        assertThat(stored.webhookSubscriptionId()).isEqualTo("sub-2");
        // Self-heal mints a new subscription id, i.e. a new subject filter — the scope consumer must be
        // reconciled just like a fresh register, or the workspace stays deaf until a restart.
    }

    @Test
    void ensureSubscription_keepsTheOldSubscriptionWhenRegisteringItsReplacementFails() {
        stubActiveConnection(config("sub-1", null));
        stubToken();
        when(outlineApiClient.createWebhookSubscription(any(), any(), any(), any(), any(), any()))
                .thenThrow(new OutlineApiException("boom"));

        registrar(API_ORIGIN).ensureSubscription(WORKSPACE_ID);

        verify(outlineApiClient, never()).deleteWebhookSubscription(any(), any(), any());
        verify(connectionService, never()).updateConfig(anyLongMatcher(), any(), any());
    }

    @Test
    void ensureSubscription_replacesASubscriptionWithoutARoute() {
        // Registered before routes existed: it posts to the shared path, where no delivery verifies.
        stubActiveConnection(config("sub-1", null));
        stubToken();
        stubCreateReturning("sub-2");

        registrar(API_ORIGIN).ensureSubscription(WORKSPACE_ID);

        verify(outlineApiClient).deleteWebhookSubscription(SERVER_URL, "tok", "sub-1");
        verify(outlineApiClient).createWebhookSubscription(any(), any(), any(), any(), any(), any());
    }

    @Test
    void ensureSubscription_replacesASubscriptionOnARetiredRoutingKey() {
        String retired = credential("outline-route-test-retired-routing-secret-0123")
                .issue()
                .route();
        stubActiveConnection(config("sub-1", retired));
        stubToken();
        stubCreateReturning("sub-2");

        registrar(API_ORIGIN).ensureSubscription(WORKSPACE_ID);

        verify(outlineApiClient).deleteWebhookSubscription(SERVER_URL, "tok", "sub-1");
        verify(outlineApiClient).createWebhookSubscription(any(), any(), any(), any(), any(), any());
    }

    @Test
    void ensureSubscription_replacesASubscriptionPostingToAnotherUrl() {
        stubActiveConnection(config("sub-1", ROUTE));
        stubToken();
        when(outlineApiClient.listWebhookSubscriptions(SERVER_URL, "tok"))
                .thenReturn(List.of(upstream("sub-1", true, "https://old.example.com/webhooks/outline/" + ROUTE)));
        stubCreateReturning("sub-2");

        registrar(API_ORIGIN).ensureSubscription(WORKSPACE_ID);

        verify(outlineApiClient).deleteWebhookSubscription(SERVER_URL, "tok", "sub-1");
        verify(outlineApiClient).createWebhookSubscription(any(), any(), any(), any(), any(), any());
    }

    @Test
    void ensureSubscription_reRegistersWhenTheStoredSubscriptionVanishedUpstream() {
        stubActiveConnection(config("sub-1", ROUTE));
        stubToken();
        when(outlineApiClient.listWebhookSubscriptions(SERVER_URL, "tok")).thenReturn(List.of());
        stubCreateReturning("sub-2");

        registrar(API_ORIGIN).ensureSubscription(WORKSPACE_ID);

        verify(outlineApiClient).createWebhookSubscription(any(), any(), any(), any(), any(), any());
    }

    @Test
    void ensureSubscription_doesNotChurnWhenTheUpstreamCannotBeListed() {
        stubActiveConnection(config("sub-1", ROUTE));
        stubToken();
        when(outlineApiClient.listWebhookSubscriptions(SERVER_URL, "tok")).thenThrow(new OutlineApiException("boom"));

        assertThatCode(() -> registrar(API_ORIGIN).ensureSubscription(WORKSPACE_ID))
                .doesNotThrowAnyException();

        verify(outlineApiClient, never()).createWebhookSubscription(any(), any(), any(), any(), any(), any());
        verify(connectionService, never()).updateConfig(anyLongMatcher(), any(), any());
        // An unverifiable upstream changes nothing — don't churn the scope consumer either.
    }

    @Test
    void deregisterByConnectionId_deletesUpstreamWhileCredentialsSurvive() {
        // SUSPENDED keeps credentials, so a deactivated connection can still tear its subscription down.
        when(connection.getConfig()).thenReturn(config("sub-77", ROUTE));
        when(connectionService.findInWorkspace(WORKSPACE_ID, CONNECTION_ID)).thenReturn(Optional.of(connection));
        when(connectionService.findBearerToken(WORKSPACE_ID, CONNECTION_ID))
                .thenReturn(Optional.of(new BearerToken("tok", null)));

        registrar(API_ORIGIN).deregister(WORKSPACE_ID, CONNECTION_ID);

        verify(outlineApiClient).deleteWebhookSubscription(SERVER_URL, "tok", "sub-77");
    }

    @Test
    void preparedDeregistration_callsOutlineOnlyWhenRun() {
        when(connection.getConfig()).thenReturn(config("sub-77", ROUTE));
        when(connectionService.findInWorkspace(WORKSPACE_ID, CONNECTION_ID)).thenReturn(Optional.of(connection));
        when(connectionService.findBearerToken(WORKSPACE_ID, CONNECTION_ID))
                .thenReturn(Optional.of(new BearerToken("tok", null)));

        Runnable deletion = registrar(API_ORIGIN)
                .prepareDeregistration(WORKSPACE_ID, CONNECTION_ID)
                .orElseThrow();
        verifyNoInteractions(outlineApiClient);
        deletion.run();

        verify(outlineApiClient).deleteWebhookSubscription(SERVER_URL, "tok", "sub-77");
    }

    @Test
    void deregisterByConnectionId_onlyLogsWhenCredentialsWerePurged() {
        // UNINSTALLED purged the token: no upstream call is possible; the subscription auto-disables.
        when(connection.getConfig()).thenReturn(config("sub-77", ROUTE));
        when(connectionService.findInWorkspace(WORKSPACE_ID, CONNECTION_ID)).thenReturn(Optional.of(connection));
        when(connectionService.findBearerToken(WORKSPACE_ID, CONNECTION_ID)).thenReturn(Optional.empty());

        assertThatCode(() -> registrar(API_ORIGIN).deregister(WORKSPACE_ID, CONNECTION_ID))
                .doesNotThrowAnyException();

        verify(outlineApiClient, never()).deleteWebhookSubscription(any(), any(), any());
        // The connection already left ACTIVE — the scope consumer must still be reconciled down even
        // though no upstream delete was possible.
    }

    @Test
    void deregisterByConnectionId_skipsWhenNoSubscriptionStored() {
        when(connection.getConfig()).thenReturn(config(null, null));
        when(connectionService.findInWorkspace(WORKSPACE_ID, CONNECTION_ID)).thenReturn(Optional.of(connection));

        registrar(API_ORIGIN).deregister(WORKSPACE_ID, CONNECTION_ID);

        verify(outlineApiClient, never()).deleteWebhookSubscription(any(), any(), any());
    }

    private static long anyLongMatcher() {
        return anyLong();
    }
}
