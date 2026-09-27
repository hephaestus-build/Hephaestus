package de.tum.cit.aet.hephaestus.integration.outline.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.security.EncryptedStringConverter;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.consumer.IntegrationNatsConsumer;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineApiClient;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineApiException;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineClientModels;
import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineWebhookSubscription;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Correctness of the change-notification subscription lifecycle: without a stored id a subscription is
 * registered and its id + generated secret stored; a stored id is verified upstream and self-healed when
 * Outline auto-disabled or lost it (but never churned when the upstream cannot be listed); revoke tears
 * the subscription down, including the deactivated-connection variant that resolves by connection id.
 * Registration is gated on a configured external URL.
 */
class OutlineWebhookRegistrarTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 7L;
    private static final long CONNECTION_ID = 31L;
    private static final String SERVER_URL = "https://outline.example.test";
    private static final String EXTERNAL_URL = "https://heph.example.com/";

    @Mock
    private ConnectionService connectionService;

    @Mock
    private OutlineApiClient outlineApiClient;

    @Mock
    private Connection connection;

    @Mock
    private IntegrationNatsConsumer natsConsumer;

    /**
     * A provider whose {@code ifAvailable} runs its consumer against {@code bean}, or is a no-op when
     * null. {@code lenient()} because most no-op tests never reach the reconcile call at all.
     */
    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(@Nullable T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        if (bean != null) {
            Mockito.lenient()
                    .doAnswer(inv -> {
                        inv.<Consumer<T>>getArgument(0).accept(bean);
                        return null;
                    })
                    .when(provider)
                    .ifAvailable(any());
        }
        return provider;
    }

    private OutlineWebhookRegistrar registrar(String externalUrl) {
        return registrar(externalUrl, natsConsumer);
    }

    private OutlineWebhookRegistrar registrar(String externalUrl, @Nullable IntegrationNatsConsumer consumer) {
        return new OutlineWebhookRegistrar(
                connectionService, outlineApiClient, new EncryptedStringConverter(), externalUrl, providerOf(consumer));
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

    private static ConnectionConfig.OutlineConfig config(@Nullable String subscriptionId, @Nullable String secret) {
        return new ConnectionConfig.OutlineConfig(SERVER_URL, subscriptionId, secret, Set.of());
    }

    private static OutlineWebhookSubscription upstream(String id, Boolean enabled) {
        return OutlineClientModels.webhookSubscription(
                id, "Hephaestus", "https://x.example/webhooks/outline", enabled, List.of());
    }

    /**
     * The subscription id changed (fresh register, self-heal re-register, or either deregister path) —
     * both {@code startConsumingScope} and {@code updateScopeConsumer} are called so the reconcile lands
     * regardless of whether the scope consumer was already running.
     */
    private void verifyScopeConsumerReconciled() {
        verify(natsConsumer).startConsumingScope(WORKSPACE_ID);
        verify(natsConsumer).updateScopeConsumer(WORKSPACE_ID);
    }

    @Test
    void ensureSubscription_registersAndStoresSubscriptionWhenNoneStored() {
        stubActiveConnection(config(null, null));
        stubToken();
        stubCreateReturning("sub-99");

        registrar(EXTERNAL_URL).ensureSubscription(WORKSPACE_ID);

        // The delivery URL is the external URL (trailing slash trimmed) + the unified webhook path.
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> secret = ArgumentCaptor.forClass(String.class);
        verify(outlineApiClient)
                .createWebhookSubscription(
                        eq(SERVER_URL), eq("tok"), anyString(), url.capture(), secret.capture(), anyList());
        assertThat(url.getValue()).isEqualTo("https://heph.example.com/webhooks/outline");
        assertThat(secret.getValue()).hasSize(64); // 256-bit hex

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UnaryOperator<ConnectionConfig>> mutator = ArgumentCaptor.forClass(UnaryOperator.class);
        verify(connectionService).updateConfig(eq(WORKSPACE_ID), eq(IntegrationKind.OUTLINE), mutator.capture());
        ConnectionConfig.OutlineConfig updated =
                (ConnectionConfig.OutlineConfig) mutator.getValue().apply(config(null, null));
        assertThat(updated.webhookSubscriptionId()).isEqualTo("sub-99");
        assertThat(updated.webhookSecret()).isEqualTo(secret.getValue());
        // Without this reconcile, the scope consumer (created at boot with only the SCM stream) never
        // picks up the new outline.<subId>.> filter until a restart.
        verifyScopeConsumerReconciled();
    }

    @Test
    void ensureSubscription_skipsWhenExternalUrlMissing() {
        registrar("").ensureSubscription(WORKSPACE_ID);

        verify(connectionService, never()).findActive(anyLongMatcher(), any());
        verifyNoInteractions(natsConsumer);
    }

    @Test
    void ensureSubscription_leavesAHealthyStoredSubscriptionAlone() {
        stubActiveConnection(config("sub-1", "sec"));
        stubToken();
        when(outlineApiClient.listWebhookSubscriptions(SERVER_URL, "tok")).thenReturn(List.of(upstream("sub-1", true)));

        registrar(EXTERNAL_URL).ensureSubscription(WORKSPACE_ID);

        verify(outlineApiClient, never()).createWebhookSubscription(any(), any(), any(), any(), any(), any());
        verify(connectionService, never()).updateConfig(anyLongMatcher(), any(), any());
        // Nothing changed upstream — the scope consumer must not be poked.
        verifyNoInteractions(natsConsumer);
    }

    @Test
    void ensureSubscription_reRegistersWhenUpstreamDisabledTheStoredSubscription() {
        // Outline auto-disables a subscription after 25 consecutive delivery failures — self-heal it.
        stubActiveConnection(config("sub-1", "sec"));
        stubToken();
        when(outlineApiClient.listWebhookSubscriptions(SERVER_URL, "tok"))
                .thenReturn(List.of(upstream("sub-1", false)));
        stubCreateReturning("sub-2");

        registrar(EXTERNAL_URL).ensureSubscription(WORKSPACE_ID);

        // One clearing mutation (drop the stale id + secret) followed by one storing mutation.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<UnaryOperator<ConnectionConfig>> mutators = ArgumentCaptor.forClass(UnaryOperator.class);
        verify(connectionService, times(2))
                .updateConfig(eq(WORKSPACE_ID), eq(IntegrationKind.OUTLINE), mutators.capture());
        ConnectionConfig.OutlineConfig cleared =
                (ConnectionConfig.OutlineConfig) mutators.getAllValues().get(0).apply(config("sub-1", "sec"));
        assertThat(cleared.webhookSubscriptionId()).isNull();
        assertThat(cleared.webhookSecret()).isNull();
        ConnectionConfig.OutlineConfig stored =
                (ConnectionConfig.OutlineConfig) mutators.getAllValues().get(1).apply(config(null, null));
        assertThat(stored.webhookSubscriptionId()).isEqualTo("sub-2");
        // Self-heal mints a new subscription id, i.e. a new subject filter — the scope consumer must be
        // reconciled just like a fresh register, or the workspace stays deaf until a restart.
        verifyScopeConsumerReconciled();
    }

    @Test
    void ensureSubscription_reRegistersWhenTheStoredSubscriptionVanishedUpstream() {
        stubActiveConnection(config("sub-1", "sec"));
        stubToken();
        when(outlineApiClient.listWebhookSubscriptions(SERVER_URL, "tok")).thenReturn(List.of());
        stubCreateReturning("sub-2");

        registrar(EXTERNAL_URL).ensureSubscription(WORKSPACE_ID);

        verify(outlineApiClient).createWebhookSubscription(any(), any(), any(), any(), any(), any());
        verifyScopeConsumerReconciled();
    }

    @Test
    void ensureSubscription_doesNotChurnWhenTheUpstreamCannotBeListed() {
        stubActiveConnection(config("sub-1", "sec"));
        stubToken();
        when(outlineApiClient.listWebhookSubscriptions(SERVER_URL, "tok")).thenThrow(new OutlineApiException("boom"));

        assertThatCode(() -> registrar(EXTERNAL_URL).ensureSubscription(WORKSPACE_ID))
                .doesNotThrowAnyException();

        verify(outlineApiClient, never()).createWebhookSubscription(any(), any(), any(), any(), any(), any());
        verify(connectionService, never()).updateConfig(anyLongMatcher(), any(), any());
        // An unverifiable upstream changes nothing — don't churn the scope consumer either.
        verifyNoInteractions(natsConsumer);
    }

    @Test
    void deregisterByConnectionId_deletesUpstreamWhileCredentialsSurvive() {
        // SUSPENDED keeps credentials, so a deactivated connection can still tear its subscription down.
        when(connection.getConfig()).thenReturn(config("sub-77", "sec"));
        when(connectionService.findInWorkspace(WORKSPACE_ID, CONNECTION_ID)).thenReturn(Optional.of(connection));
        when(connectionService.findBearerToken(WORKSPACE_ID, CONNECTION_ID))
                .thenReturn(Optional.of(new BearerToken("tok", null)));

        registrar(EXTERNAL_URL).deregister(WORKSPACE_ID, CONNECTION_ID);

        verify(outlineApiClient).deleteWebhookSubscription(SERVER_URL, "tok", "sub-77");
        verifyScopeConsumerReconciled();
    }

    @Test
    void preparedDeregistration_callsOutlineOnlyWhenRun() {
        when(connection.getConfig()).thenReturn(config("sub-77", "sec"));
        when(connectionService.findInWorkspace(WORKSPACE_ID, CONNECTION_ID)).thenReturn(Optional.of(connection));
        when(connectionService.findBearerToken(WORKSPACE_ID, CONNECTION_ID))
                .thenReturn(Optional.of(new BearerToken("tok", null)));

        Runnable deletion = registrar(EXTERNAL_URL)
                .prepareDeregistration(WORKSPACE_ID, CONNECTION_ID)
                .orElseThrow();
        verifyNoInteractions(outlineApiClient);
        deletion.run();

        verify(outlineApiClient).deleteWebhookSubscription(SERVER_URL, "tok", "sub-77");
    }

    @Test
    void deregisterByConnectionId_onlyLogsWhenCredentialsWerePurged() {
        // UNINSTALLED purged the token: no upstream call is possible; the subscription auto-disables.
        when(connection.getConfig()).thenReturn(config("sub-77", "sec"));
        when(connectionService.findInWorkspace(WORKSPACE_ID, CONNECTION_ID)).thenReturn(Optional.of(connection));
        when(connectionService.findBearerToken(WORKSPACE_ID, CONNECTION_ID)).thenReturn(Optional.empty());

        assertThatCode(() -> registrar(EXTERNAL_URL).deregister(WORKSPACE_ID, CONNECTION_ID))
                .doesNotThrowAnyException();

        verify(outlineApiClient, never()).deleteWebhookSubscription(any(), any(), any());
        // The connection already left ACTIVE — the scope consumer must still be reconciled down even
        // though no upstream delete was possible.
        verifyScopeConsumerReconciled();
    }

    @Test
    void deregisterByConnectionId_skipsWhenNoSubscriptionStored() {
        when(connection.getConfig()).thenReturn(config(null, null));
        when(connectionService.findInWorkspace(WORKSPACE_ID, CONNECTION_ID)).thenReturn(Optional.of(connection));

        registrar(EXTERNAL_URL).deregister(WORKSPACE_ID, CONNECTION_ID);

        verify(outlineApiClient, never()).deleteWebhookSubscription(any(), any(), any());
        verifyNoInteractions(natsConsumer);
    }

    // --- absent NATS-consumer ObjectProvider bean: every reconcile-triggering call site must not NPE ---

    /** The four call sites whose scope-consumer reconcile must tolerate a missing {@code IntegrationNatsConsumer} bean. */
    private enum NoNatsConsumerBeanCase {
        ENSURE_SUBSCRIPTION_REGISTERS,
        ENSURE_SUBSCRIPTION_RE_REGISTERS,
        DEREGISTER_BY_CONNECTION_ID,
    }

    @ParameterizedTest
    @EnumSource(NoNatsConsumerBeanCase.class)
    @DisplayName("never NPEs when the NATS consumer ObjectProvider bean is absent")
    void neverNPEsWithNoNatsConsumerBean(NoNatsConsumerBeanCase testCase) {
        switch (testCase) {
            case ENSURE_SUBSCRIPTION_REGISTERS -> {
                stubActiveConnection(config(null, null));
                stubToken();
                stubCreateReturning("sub-99");

                assertThatCode(() -> registrar(EXTERNAL_URL, null).ensureSubscription(WORKSPACE_ID))
                        .doesNotThrowAnyException();

                verify(connectionService).updateConfig(eq(WORKSPACE_ID), eq(IntegrationKind.OUTLINE), any());
            }
            case ENSURE_SUBSCRIPTION_RE_REGISTERS -> {
                stubActiveConnection(config("sub-1", "sec"));
                stubToken();
                when(outlineApiClient.listWebhookSubscriptions(SERVER_URL, "tok"))
                        .thenReturn(List.of(upstream("sub-1", false)));
                stubCreateReturning("sub-2");

                assertThatCode(() -> registrar(EXTERNAL_URL, null).ensureSubscription(WORKSPACE_ID))
                        .doesNotThrowAnyException();

                verify(outlineApiClient).createWebhookSubscription(any(), any(), any(), any(), any(), any());
            }
            case DEREGISTER_BY_CONNECTION_ID -> {
                when(connection.getConfig()).thenReturn(config("sub-77", "sec"));
                when(connectionService.findInWorkspace(WORKSPACE_ID, CONNECTION_ID))
                        .thenReturn(Optional.of(connection));
                when(connectionService.findBearerToken(WORKSPACE_ID, CONNECTION_ID))
                        .thenReturn(Optional.of(new BearerToken("tok", null)));

                assertThatCode(() -> registrar(EXTERNAL_URL, null).deregister(WORKSPACE_ID, CONNECTION_ID))
                        .doesNotThrowAnyException();

                verify(outlineApiClient).deleteWebhookSubscription(SERVER_URL, "tok", "sub-77");
            }
        }
    }

    private static long anyLongMatcher() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}
