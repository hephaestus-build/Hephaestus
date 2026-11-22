package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.core.auth.AuthPropertiesFixture;
import de.tum.cit.aet.hephaestus.core.web.PublicApiUrls;
import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.core.webhook.WebhookPropertiesFixture;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IntegrationAttentionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.repair.ProviderResourceRegistrations;
import de.tum.cit.aet.hephaestus.integration.core.events.IntegrationAttentionChangedEvent.Problem;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter.Resource;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter.ResourceType;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient.WebhookConfig;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient.WebhookInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.credentials.GitLabTokenLifecycleService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook.GitLabRouteCredential;
import de.tum.cit.aet.hephaestus.observability.ShippedLogEncoder;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceLookup;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceRepositoryMonitors;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Unit tests for {@link GitLabWebhookService}. The service reads/writes the {@code GitLabConfig} on
 * the active GitLab Connection via {@link ConnectionService}; these tests back that registry with a
 * small in-memory map stand-in to avoid an integration test container.
 */
@Tag("unit")
class GitLabWebhookServiceTest extends BaseUnitTest {

    @Mock
    private ObjectProvider<GitLabWebhookClient> webhookClientProvider;

    @Mock
    private GitLabWebhookClient webhookClient;

    @Mock
    private WorkspaceLookup workspaceLookup;

    @Mock
    private ConnectionService connectionService;

    @Mock
    private ObjectProvider<GitLabTokenLifecycleService> tokenLifecycle;

    @Mock
    private ProviderResourceRegistrations repairs;

    @Mock
    private WorkspaceRepositoryMonitors monitors;

    @Mock
    private IntegrationAttentionService attention;

    @Mock
    private ObjectProvider<LockProvider> lockProvider;

    @Mock
    private LockProvider locks;

    private final Map<UUID, ConnectionConfig.GitLabConfig> registrations = new HashMap<>();
    private GitLabWebhookService webhookService;

    private Workspace workspace;
    private Map<Long, ConnectionConfig.GitLabConfig> gitLabConfigs;
    private Map<Long, BearerToken> gitLabBearerTokens;

    private static final String API_ORIGIN = "https://app.example.com";
    private static final String SECRET = "a]RkF9P2s#Lm7$xQ4wN!vB3yJ6tH0dCe";
    private static final String KEY = "routing-key-for-webhook-service-tests-0123";
    private static final String NEXT_KEY = "next-routing-key-for-webhook-service-tests";
    private static final long CONNECTION_ID = 5L;
    private static final GitLabRouteCredential.Route ROUTE =
            new GitLabRouteCredential.Route(CONNECTION_ID, 1L, "https://gitlab.com", 42L, "my-org");

    private GitLabRouteCredential routeCredential;
    private final List<WebhookInfo> hooks = new ArrayList<>();
    private final List<WebhookConfig> registered = new ArrayList<>();
    private long nextHookId = 100L;

    private static GitLabRouteCredential credential(@Nullable String secret, @Nullable String previous) {
        return new GitLabRouteCredential(
                WebhookPropertiesFixture.configured(SECRET, new WebhookProperties.Routing(secret, previous)));
    }

    private GitLabWebhookService service(GitLabRouteCredential credential) {
        return new GitLabWebhookService(
                webhookClientProvider,
                new PublicApiUrls(AuthPropertiesFixture.withIssuer(URI.create(API_ORIGIN + "/api"))),
                credential,
                workspaceLookup,
                connectionService,
                repairs,
                tokenLifecycle,
                monitors,
                attention,
                lockProvider);
    }

    /** The hook URL {@code credential} issues for {@code route}. */
    private static String connectionUrl(GitLabRouteCredential credential, GitLabRouteCredential.Route route) {
        GitLabRouteCredential.Issued issued = credential.issue(route);
        return API_ORIGIN + "/webhooks/gitlab/connections/" + CONNECTION_ID + "/" + issued.keyId() + "/"
                + issued.routeId();
    }

    private static String connectionUrl(GitLabRouteCredential credential) {
        return connectionUrl(credential, ROUTE);
    }

    /** The group's hooks as GitLab keeps them: listed, fetched, created and deleted through the client. */
    private void fakeGroupHooks() {
        lenient().when(webhookClientProvider.getIfAvailable()).thenReturn(webhookClient);
        // doAnswer, so it also replaces a stub that throws.
        lenient().doAnswer(inv -> List.copyOf(hooks)).when(webhookClient).listGroupWebhooks(1L, 42L);
        lenient()
                .when(webhookClient.getGroupWebhook(eq(1L), eq(42L), anyLong()))
                .thenAnswer(inv -> hooks.stream()
                        .filter(hook -> hook.id() == (long) inv.getArgument(2))
                        .findFirst());
        lenient()
                .when(webhookClient.registerGroupWebhook(eq(1L), eq(42L), any(WebhookConfig.class)))
                .thenAnswer(inv -> {
                    WebhookConfig config = inv.getArgument(2);
                    registered.add(config);
                    WebhookInfo hook = new WebhookInfo(nextHookId++, config.url());
                    hooks.add(hook);
                    return hook;
                });
    }

    private void storeHook(WebhookInfo hook) {
        hooks.add(hook);
        bindGitLabConfig(1L, new ConnectionConfig.GitLabConfig("https://gitlab.com", 42L, hook.id(), Set.of(), null));
    }

    private void storeHook(long webhookId, String url) {
        storeHook(new WebhookInfo(webhookId, url));
    }

    @BeforeEach
    void setUp() {
        lenient().when(repairs.beginRegistration(anyLong(), anyLong(), any())).thenAnswer(call -> {
            UUID id = UUID.randomUUID();
            registrations.put(id, currentConfig(1L));
            return id;
        });
        lenient()
                .when(repairs.completeRegistration(anyLong(), anyLong(), any(), any()))
                .thenAnswer(call -> {
                    if (!Objects.equals(registrations.get(call.getArgument(2)), currentConfig(1L))) return false;
                    UnaryOperator<ConnectionConfig> mutate = call.getArgument(3);
                    bindGitLabConfig(1L, (ConnectionConfig.GitLabConfig) mutate.apply(currentConfig(1L)));
                    return true;
                });
        lenient()
                .when(repairs.recordOwnership(anyLong(), anyLong(), any(), any()))
                .thenAnswer(call -> {
                    UnaryOperator<ConnectionConfig> mutate = call.getArgument(3);
                    bindGitLabConfig(1L, (ConnectionConfig.GitLabConfig) mutate.apply(currentConfig(1L)));
                    return true;
                });
        lenient().when(lockProvider.getIfAvailable()).thenReturn(locks);
        lenient().when(locks.lock(any())).thenReturn(Optional.of(mock(SimpleLock.class)));
        routeCredential = credential(KEY, null);
        webhookService = service(routeCredential);
        Connection connection = mock(Connection.class);
        lenient().when(connection.getId()).thenReturn(CONNECTION_ID);
        lenient().when(connection.getConfig()).thenAnswer(inv -> gitLabConfigs.get(1L));
        lenient()
                .when(connectionService.findActive(anyLong(), eq(IntegrationKind.GITLAB)))
                .thenAnswer(inv -> gitLabConfigs.containsKey((long) inv.getArgument(0))
                        ? Optional.of(connection)
                        : Optional.empty());

        workspace = new Workspace();
        workspace.setAccountLogin("my-org");
        ReflectionTestUtils.setField(workspace, "id", 1L);

        gitLabConfigs = new HashMap<>();
        gitLabBearerTokens = new HashMap<>();

        // Default: workspace is a GitLab workspace with a token. Tests that need
        // to flip this out (non-GitLab, missing token) override per-test.
        bindGitLabConfig(1L, new ConnectionConfig.GitLabConfig("https://gitlab.com", 42L, null, Set.of(), null));
        gitLabBearerTokens.put(1L, new BearerToken("glpat-test-token", null));

        // lenient() — each Nested test exercises a different code path, so a shared setUp stub may go
        // unused per test, which strict-stub mode would otherwise reject.
        lenient().when(connectionService.findActiveProviderKind(anyLong())).thenAnswer(inv -> {
            long id = inv.getArgument(0);
            return gitLabConfigs.containsKey(id) ? Optional.of(IntegrationKind.GITLAB) : Optional.empty();
        });
        lenient().when(connectionService.findActiveGitLabConfig(anyLong())).thenAnswer(inv -> {
            long id = inv.getArgument(0);
            return Optional.ofNullable(gitLabConfigs.get(id));
        });
        lenient()
                .when(connectionService.findActiveBearerToken(anyLong(), eq(IntegrationKind.GITLAB)))
                .thenAnswer(inv -> {
                    long id = inv.getArgument(0);
                    return Optional.ofNullable(gitLabBearerTokens.get(id));
                });
        lenient()
                .when(connectionService.updateConfig(anyLong(), eq(IntegrationKind.GITLAB), any()))
                .thenAnswer(this::applyUpdateConfig);
    }

    @Test
    void shouldRegisterOnlyWithARoutingSecret() {
        when(webhookClientProvider.getIfAvailable()).thenReturn(webhookClient);

        assertThat(webhookService.isRegistrationEnabled()).isTrue();
        assertThat(service(credential(null, null)).isRegistrationEnabled()).isFalse();
    }

    private void bindGitLabConfig(long workspaceId, ConnectionConfig.GitLabConfig cfg) {
        gitLabConfigs.put(workspaceId, cfg);
    }

    @SuppressWarnings("unchecked")
    private Optional<Object> applyUpdateConfig(InvocationOnMock inv) {
        long id = inv.getArgument(0);
        UnaryOperator<ConnectionConfig> mutator = inv.getArgument(2);
        ConnectionConfig.GitLabConfig current = gitLabConfigs.get(id);
        if (current == null) return Optional.empty();
        ConnectionConfig.GitLabConfig next = (ConnectionConfig.GitLabConfig) mutator.apply(current);
        gitLabConfigs.put(id, next);
        return Optional.empty();
    }

    private ConnectionConfig.GitLabConfig currentConfig(long workspaceId) {
        ConnectionConfig.GitLabConfig config = gitLabConfigs.get(workspaceId);
        assertNotNull(config);
        return config;
    }

    @Nested
    class RegisterWebhook {

        @BeforeEach
        void setUp() {
            fakeGroupHooks();
        }

        @Test
        void shouldSkipForNonGitLab() {
            gitLabConfigs.remove(1L);

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.registered()).isFalse();
            assertThat(result.reason()).isEqualTo(WebhookSetupResult.Reason.NOT_GITLAB);
        }

        @Test
        void shouldSkipWhenClientUnavailable() {
            when(webhookClientProvider.getIfAvailable()).thenReturn(null);

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.registered()).isFalse();
            assertThat(result.reason()).isEqualTo(WebhookSetupResult.Reason.CLIENT_UNAVAILABLE);
        }

        @Test
        void shouldRegisterTheConnectionHookWithItsOwnCredential() {
            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.registered()).isTrue();
            GitLabRouteCredential.Issued issued = routeCredential.issue(ROUTE);
            assertThat(registered).singleElement().satisfies(config -> {
                assertThat(config.url()).startsWith(connectionUrl(routeCredential) + "?hephaestus_resource=");
                assertThat(routeCredential.verify(config.token(), CONNECTION_ID, issued.keyId(), issued.routeId()))
                        .contains(ROUTE);
                assertThat(config.token()).isNotEqualTo(SECRET);
            });
            assertThat(currentConfig(1L).gitlabGroupId()).isEqualTo(42L);
            assertThat(currentConfig(1L).gitlabWebhookId()).isEqualTo(result.webhookId());
        }

        @Test
        void shouldUseAFreshOwnershipMarkerRatherThanAdoptAnUnrecordedHook() {
            hooks.add(new WebhookInfo(77L, connectionUrl(routeCredential)));
            hooks.add(new WebhookInfo(78L, API_ORIGIN + "/webhooks/gitlab"));

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.webhookId()).isEqualTo(100L);
            assertThat(registered).hasSize(1);
        }

        @Test
        void shouldRecordTheOwnershipOfAReleaseCandidateHookAndKeepIt() {
            String url = connectionUrl(routeCredential);
            storeHook(99L, url);

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.webhookId()).isEqualTo(99L);
            assertThat(registered).isEmpty();
            assertThat(currentConfig(1L).gitlabWebhookId()).isEqualTo(99L);
            assertThat(currentConfig(1L).webhookOwnershipHash()).isEqualTo(ProviderResourceAdapter.fingerprint(url));
        }

        @Test
        void shouldLeaveTheStoredHookAloneWhenItsOwnershipIsRecorded() {
            String url = connectionUrl(routeCredential);
            hooks.add(new WebhookInfo(99L, url));
            bindGitLabConfig(
                    1L,
                    new ConnectionConfig.GitLabConfig("https://gitlab.com", 42L, 99L, Set.of(), null)
                            .withWebhookOwnership(99L, ProviderResourceAdapter.fingerprint(url), null));

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.webhookId()).isEqualTo(99L);
            assertThat(registered).isEmpty();
            verify(repairs, never()).recordOwnership(anyLong(), anyLong(), any(), any());
        }

        @Test
        void shouldNotRecordTheOwnershipOfAMarkedHookOrOneOnAnotherOrigin() {
            String url = connectionUrl(routeCredential);
            for (String foreign : List.of(
                    url + "?hephaestus_resource=unrecorded",
                    "https://elsewhere.example" + URI.create(url).getRawPath())) {
                hooks.clear();
                storeHook(99L, foreign);

                webhookService.registerWebhook(workspace);

                verify(repairs, never()).recordOwnership(anyLong(), anyLong(), any(), any());
            }
        }

        @Test
        void shouldNotRecordTheOwnershipOfAHookUnderAKeyThisServerDoesNotAccept() {
            storeHook(99L, connectionUrl(credential(NEXT_KEY, null)));

            webhookService.registerWebhook(workspace);

            verify(repairs, never()).recordOwnership(anyLong(), anyLong(), any(), any());
        }

        @Test
        void shouldNotRecordTheOwnershipOfAHookOnAnotherRoute() {
            storeHook(99L, API_ORIGIN + "/webhooks/gitlab/connections/" + (CONNECTION_ID + 1) + "/key/route");

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            verify(repairs, never()).recordOwnership(anyLong(), anyLong(), any(), any());
            assertThat(result.webhookId()).isEqualTo(100L);
            assertThat(registered).hasSize(1);
        }

        @Test
        void shouldRegisterBesideTheSharedLegacyHookWithoutTouchingIt() {
            storeHook(99L, API_ORIGIN + "/webhooks/gitlab");

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.webhookId()).isEqualTo(100L);
            assertThat(currentConfig(1L).gitlabWebhookId()).isEqualTo(100L);
            assertThat(hooks).extracting(WebhookInfo::id).containsExactlyInAnyOrder(99L, 100L);
            assertThat(registered).noneMatch(config -> config.url().equals(API_ORIGIN + "/webhooks/gitlab"));
        }

        @Test
        void shouldReRegisterWhenTheStoredHookWasDeleted() {
            storeHook(99L, connectionUrl(routeCredential));
            hooks.clear();

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.webhookId()).isEqualTo(100L);
        }

        @Test
        void shouldRetireOwnPreviousKeyHookOnlyAfterTheNewOneIsRecorded() {
            GitLabRouteCredential rotating = credential(NEXT_KEY, KEY);
            storeHook(99L, connectionUrl(routeCredential));

            WebhookSetupResult result = service(rotating).registerWebhook(workspace);

            assertThat(result.webhookId()).isEqualTo(100L);
            assertThat(hooks).extracting(WebhookInfo::id).containsExactlyInAnyOrder(99L, 100L);
            verify(repairs).retireCurrentHook(1L, CONNECTION_ID);
        }

        @Test
        void shouldReplaceItsOwnHookWhenTheConnectedGroupWasRenamed() {
            storeHook(
                    99L,
                    connectionUrl(
                            routeCredential,
                            new GitLabRouteCredential.Route(
                                    CONNECTION_ID, 1L, "https://gitlab.com", 42L, "my-old-org")));

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.webhookId()).isEqualTo(100L);
            assertThat(hooks).extracting(WebhookInfo::id).containsExactlyInAnyOrder(99L, 100L);
        }

        @Test
        void shouldLeaveAHookOfANewerKeyToTheServerThatHoldsIt() {
            GitLabRouteCredential newer = credential(NEXT_KEY, KEY);
            storeHook(99L, connectionUrl(newer));

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.registered()).isFalse();
            assertThat(result.reason()).isEqualTo(WebhookSetupResult.Reason.ROUTING_KEY_UNKNOWN);
            assertThat(registered).isEmpty();
            assertThat(hooks).extracting(WebhookInfo::id).containsExactly(99L);
            assertThat(currentConfig(1L).gitlabWebhookId()).isEqualTo(99L);
        }

        @Test
        void shouldNotRecordItsHookOverOneAConcurrentRegistrationRecordedFirst() {
            storeHook(99L, API_ORIGIN + "/webhooks/gitlab");
            when(webhookClient.registerGroupWebhook(eq(1L), eq(42L), any(WebhookConfig.class)))
                    .thenAnswer(inv -> {
                        WebhookConfig config = inv.getArgument(2);
                        // A registration under a newer key recorded its hook while this one was creating its own.
                        storeHook(555L, connectionUrl(credential(NEXT_KEY, KEY)));
                        WebhookInfo hook = new WebhookInfo(nextHookId++, config.url());
                        hooks.add(hook);
                        return hook;
                    });

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.registered()).isFalse();
            assertThat(currentConfig(1L).gitlabWebhookId()).isEqualTo(555L);
        }

        @Test
        void shouldKeepTheHookAConcurrentRegistrationOfTheSameRouteRecordedWhenItsOwnRecordLoses() {
            storeHook(99L, API_ORIGIN + "/webhooks/gitlab");
            when(repairs.completeRegistration(eq(1L), eq(CONNECTION_ID), any(), any()))
                    .thenAnswer(inv -> {
                        // The other registration adopted the same hook and recorded it first; this write conflicts.
                        bindGitLabConfig(1L, currentConfig(1L).withGitlabWebhookId(100L));
                        throw new ObjectOptimisticLockingFailureException(Connection.class, 1L);
                    });

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.registered()).isFalse();
            assertThat(currentConfig(1L).gitlabWebhookId()).isEqualTo(100L);
            assertThat(hooks).extracting(WebhookInfo::id).containsExactlyInAnyOrder(99L, 100L);
        }

        @Test
        void shouldRecordOneReturnedHookWithoutBlindDuplicateDeletion() {
            when(webhookClient.registerGroupWebhook(eq(1L), eq(42L), any(WebhookConfig.class)))
                    .thenAnswer(inv -> {
                        WebhookConfig config = inv.getArgument(2);
                        registered.add(config);
                        // A concurrent registration of the same route landed first, with the same token.
                        hooks.add(new WebhookInfo(100L, config.url()));
                        hooks.add(new WebhookInfo(101L, config.url()));
                        return new WebhookInfo(101L, config.url());
                    });

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.webhookId()).isEqualTo(100L);
            assertThat(hooks).extracting(WebhookInfo::id).containsExactlyInAnyOrder(100L, 101L);
            assertThat(routeCredential.issue(ROUTE).token())
                    .isEqualTo(registered.getFirst().token());
        }

        @Test
        void shouldSkipWithoutRoutingKey() {
            WebhookSetupResult result = service(credential(null, null)).registerWebhook(workspace);

            assertThat(result.registered()).isFalse();
            assertThat(result.reason()).isEqualTo(WebhookSetupResult.Reason.ROUTING_SECRET_MISSING);
            assertThat(result.reason()).isEqualTo(WebhookSetupResult.Reason.ROUTING_SECRET_MISSING);
            assertThat(registered).isEmpty();
        }

        @Test
        void shouldReportRegistrationStatusWithoutLoggingTheProviderBody() {
            when(webhookClientProvider.getIfAvailable()).thenReturn(webhookClient);
            when(webhookClient.listGroupWebhooks(1L, 42L)).thenReturn(List.of());
            when(webhookClient.registerGroupWebhook(eq(1L), eq(42L), any(WebhookConfig.class)))
                    .thenThrow(WebClientResponseException.create(
                            500,
                            "Server Error",
                            HttpHeaders.EMPTY,
                            "private-response-body with private-webhook-secret".getBytes(StandardCharsets.UTF_8),
                            StandardCharsets.UTF_8));
            Logger logger = (Logger) LoggerFactory.getLogger(GitLabWebhookService.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                WebhookSetupResult result = webhookService.registerWebhook(workspace);
                assertThat(result.registered()).isFalse();
                assertThat(result.reason()).isEqualTo(WebhookSetupResult.Reason.API_ERROR);
                assertThat(result.httpStatus()).isEqualTo(500);
                var failures = appender.list.stream()
                        .filter(event -> event.getLevel() == Level.WARN)
                        .toList();
                assertThat(failures).hasSize(1);
                ILoggingEvent event = failures.getFirst();
                assertThat(event.getKeyValuePairs()).isNotNull();
                var fields = event.getKeyValuePairs().stream()
                        .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
                assertThat(fields)
                        .containsExactlyInAnyOrderEntriesOf(Map.of(
                                "event.name",
                                "integration.webhook.registration.failed",
                                "integration.kind",
                                IntegrationKind.GITLAB,
                                "workspace.id",
                                1L,
                                "http.response.status_code",
                                500));
                assertThat(event.getFormattedMessage()).isEqualTo("GitLab webhook registration failed");
                assertThat(event.getArgumentArray()).isNullOrEmpty();
                assertThat(event.getThrowableProxy()).isNull();
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }
        }

        @Test
        void shouldReturnFailedOn403() {
            when(webhookClientProvider.getIfAvailable()).thenReturn(webhookClient);
            when(webhookClient.listGroupWebhooks(1L, 42L)).thenReturn(List.of());
            when(webhookClient.registerGroupWebhook(eq(1L), eq(42L), any(WebhookConfig.class)))
                    .thenThrow(WebClientResponseException.create(
                            403, "Forbidden", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));

            workspace.setAccountLogin("private-account-login-canary");
            Logger logger = (Logger) LoggerFactory.getLogger(GitLabWebhookService.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                WebhookSetupResult result = webhookService.registerWebhook(workspace);

                assertThat(result.registered()).isFalse();
                assertThat(result.reason()).isEqualTo(WebhookSetupResult.Reason.PERMISSION_DENIED);
                assertThat(result.httpStatus()).isEqualTo(403);
                assertThat(appender.list)
                        .anySatisfy(
                                event -> assertThat(event.getKeyValuePairs()).anySatisfy(pair -> {
                                    assertThat(pair.key).isEqualTo("http.response.status_code");
                                    assertThat(pair.value).isEqualTo(403);
                                }))
                        .allSatisfy(event -> assertThat(ShippedLogEncoder.encode(event))
                                .doesNotContain("private-account-login-canary"));
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }
        }

        @Test
        void shouldSkipWithoutRoutingCredentials() {
            WebhookProperties unconfigured =
                    WebhookPropertiesFixture.configured("", new WebhookProperties.Routing(null, null));
            var service = new GitLabWebhookService(
                    webhookClientProvider,
                    new PublicApiUrls(AuthPropertiesFixture.withIssuer(URI.create(API_ORIGIN))),
                    new GitLabRouteCredential(unconfigured),
                    workspaceLookup,
                    connectionService,
                    mock(ProviderResourceRegistrations.class),
                    tokenLifecycle,
                    monitors,
                    attention,
                    lockProvider);

            WebhookSetupResult result = service.registerWebhook(workspace);

            assertThat(result.registered()).isFalse();
            assertThat(result.reason()).isEqualTo(WebhookSetupResult.Reason.ROUTING_SECRET_MISSING);
        }
    }

    @Nested
    class CheckWebhookHealth {

        @BeforeEach
        void bindActiveHookedWorkspace() {
            fakeGroupHooks();
            when(workspaceLookup.active()).thenReturn(List.of(workspace));
        }

        @Test
        void shouldReportARegistrationRefusalWithoutTheAccountLogin() {
            workspace.setAccountLogin("private-account-login-canary");
            bindGitLabConfig(1L, new ConnectionConfig.GitLabConfig("https://gitlab.com", 42L, 99L, Set.of(), null));
            when(webhookClient.registerGroupWebhook(eq(1L), eq(42L), any(WebhookConfig.class)))
                    .thenThrow(WebClientResponseException.create(
                            403, "Forbidden", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));
            Logger logger = (Logger) LoggerFactory.getLogger(GitLabWebhookService.class);
            var appender = new ListAppender<ILoggingEvent>();
            appender.start();
            logger.addAppender(appender);
            try {
                webhookService.checkWebhookHealth();

                assertThat(appender.list)
                        .anySatisfy(event ->
                                assertThat(event.getFormattedMessage()).isEqualTo("GitLab webhook registration failed"))
                        .allSatisfy(event -> assertThat(ShippedLogEncoder.encode(event))
                                .doesNotContain("private-account-login-canary"));
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }
        }

        @Test
        void leavesExecutableWebhookUntouchedAndRecordsTheOwnershipItLacks() {
            String url = connectionUrl(routeCredential);
            storeHook(new WebhookInfo(99L, url, "executable"));

            webhookService.checkWebhookHealth();

            verify(webhookClient, never()).registerGroupWebhook(anyLong(), anyLong(), any());
            assertThat(currentConfig(1L).gitlabWebhookId()).isEqualTo(99L);
            assertThat(currentConfig(1L).webhookOwnershipHash()).isEqualTo(ProviderResourceAdapter.fingerprint(url));
        }

        @Test
        void shouldKeepAStoredHookAtAPreviousPathOnTheSameOrigin() {
            String canonical = connectionUrl(routeCredential);
            String previous = API_ORIGIN + "/hooks" + URI.create(canonical).getRawPath();
            storeHook(new WebhookInfo(99L, previous, "executable"));

            webhookService.checkWebhookHealth();

            assertThat(hooks).extracting(WebhookInfo::id).containsExactlyInAnyOrder(99L, 100L);
            assertThat(registered.getFirst().url()).startsWith(canonical + "?hephaestus_resource=");
            assertThat(currentConfig(1L).gitlabWebhookId()).isEqualTo(100L);
        }

        @Test
        void reRegistersAutoDisabledWebhook() {
            // GitLab kept the hook row but flipped it to alert_status=disabled — an existence check
            // alone would pass forever, so this is the invisible-failure the health check must heal.
            storeHook(new WebhookInfo(99L, connectionUrl(routeCredential), "disabled"));

            webhookService.checkWebhookHealth();

            // The disabled hook is deleted first so registerWebhook's adopt-by-URL step can't re-adopt it,
            // then a fresh hook is registered and its id persisted.
            verify(repairs).retireCurrentHook(1L, CONNECTION_ID);
            verify(webhookClient).registerGroupWebhook(eq(1L), eq(42L), any(WebhookConfig.class));
            assertThat(currentConfig(1L).gitlabWebhookId()).isEqualTo(100L);
        }

        @Test
        void leavesADisabledSharedLegacyHookInPlace() {
            storeHook(new WebhookInfo(99L, API_ORIGIN + "/webhooks/gitlab", "disabled"));

            webhookService.checkWebhookHealth();

            assertThat(hooks).extracting(WebhookInfo::id).containsExactlyInAnyOrder(99L, 100L);
            assertThat(currentConfig(1L).gitlabWebhookId()).isEqualTo(100L);
        }

        @Test
        void replacesAnEnabledStoredHookOnTheRemovedSharedEndpoint() {
            // A workspace whose stored id is a hook on /webhooks/gitlab gets refusals for every delivery while GitLab
            // still reports the hook as executable. Its own connection hook is registered; the foreign one stays.
            storeHook(new WebhookInfo(99L, API_ORIGIN + "/webhooks/gitlab", "executable"));

            webhookService.checkWebhookHealth();

            verify(webhookClient).registerGroupWebhook(eq(1L), eq(42L), any(WebhookConfig.class));
            assertThat(hooks).extracting(WebhookInfo::id).containsExactlyInAnyOrder(99L, 100L);
            assertThat(currentConfig(1L).gitlabWebhookId()).isEqualTo(100L);
        }

        @Test
        void reRegistersExternallyDeletedWebhook() {
            storeHook(99L, connectionUrl(routeCredential));
            hooks.clear();

            webhookService.checkWebhookHealth();

            // Nothing to delete for a row that's already gone; a fresh hook is registered.
            verify(webhookClient).registerGroupWebhook(eq(1L), eq(42L), any(WebhookConfig.class));
            assertThat(currentConfig(1L).gitlabWebhookId()).isEqualTo(100L);
        }
    }

    /**
     * GitLab Community Edition has no group hook API and answers 404; an unlicensed instance, or a token without the
     * Owner role, answers 403. Either way the connection keeps one hook on each monitored project.
     */
    @Nested
    class ProjectHooks {

        private final Map<Long, List<WebhookInfo>> projectHooks = new HashMap<>();
        private final List<Long> registeredOn = new ArrayList<>();
        private final List<Resource> released = new ArrayList<>();
        private final Set<Long> refusing = new HashSet<>();
        private final List<RepositoryToMonitor> monitored = new ArrayList<>();

        @BeforeEach
        void setUp() {
            lenient().when(webhookClientProvider.getIfAvailable()).thenReturn(webhookClient);
            refuseGroupHooks(HttpStatus.NOT_FOUND);
            lenient().when(monitors.monitors(1L)).thenAnswer(inv -> List.copyOf(monitored));
            lenient().when(webhookClient.listProjectWebhooks(eq(1L), anyLong())).thenAnswer(inv -> {
                long project = inv.getArgument(1);
                if (refusing.contains(project)) throw refusal(HttpStatus.FORBIDDEN);
                return List.copyOf(projectHooks.getOrDefault(project, List.of()));
            });
            lenient()
                    .when(webhookClient.getProjectWebhook(eq(1L), anyLong(), anyLong()))
                    .thenAnswer(inv -> projectHooks.getOrDefault((long) inv.getArgument(1), List.of()).stream()
                            .filter(hook -> hook.id() == (long) inv.getArgument(2))
                            .findFirst());
            lenient()
                    .when(webhookClient.registerProjectWebhook(eq(1L), anyLong(), any(WebhookConfig.class)))
                    .thenAnswer(inv -> {
                        long project = inv.getArgument(1);
                        WebhookConfig config = inv.getArgument(2);
                        registered.add(config);
                        registeredOn.add(project);
                        WebhookInfo hook = new WebhookInfo(nextHookId++, config.url());
                        projectHooks
                                .computeIfAbsent(project, ignored -> new ArrayList<>())
                                .add(hook);
                        return hook;
                    });
            lenient()
                    .doAnswer(inv -> {
                        released.addAll(inv.getArgument(2));
                        UnaryOperator<ConnectionConfig> update = inv.getArgument(3);
                        bindGitLabConfig(1L, (ConnectionConfig.GitLabConfig) update.apply(currentConfig(1L)));
                        return null;
                    })
                    .when(repairs)
                    .releaseHooks(anyLong(), anyLong(), any(), any());
        }

        private void refuseGroupHooks(HttpStatus status) {
            lenient().doThrow(refusal(status)).when(webhookClient).listGroupWebhooks(1L, 42L);
        }

        private WebClientResponseException refusal(HttpStatus status) {
            return WebClientResponseException.create(
                    status.value(), status.getReasonPhrase(), HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
        }

        private void monitor(long... projectIds) {
            monitored.clear();
            for (long projectId : projectIds) {
                RepositoryToMonitor monitor = new RepositoryToMonitor();
                monitor.setNativeId(projectId);
                monitored.add(monitor);
            }
        }

        private Map<Long, Long> storedHooks() {
            return currentConfig(1L).projectHooks().stream()
                    .collect(Collectors.toMap(
                            ConnectionConfig.GitLabProjectHook::projectId, ConnectionConfig.GitLabProjectHook::hookId));
        }

        @Test
        void shouldRegisterAHookOnEachMonitoredProjectWhenGitLabHasNoGroupHooks() {
            monitor(7L, 8L);

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.registered()).isTrue();
            assertThat(result.reason()).isEqualTo(WebhookSetupResult.Reason.PROJECT_HOOKS_REGISTERED);
            assertThat(registeredOn).containsExactly(7L, 8L);
            GitLabRouteCredential.Issued issued = routeCredential.issue(ROUTE);
            assertThat(registered).hasSize(2).allSatisfy(config -> {
                assertThat(config.url()).startsWith(connectionUrl(routeCredential) + "?hephaestus_resource=");
                assertThat(routeCredential.verify(config.token(), CONNECTION_ID, issued.keyId(), issued.routeId()))
                        .contains(ROUTE);
            });
            assertThat(registered.get(0).url()).isEqualTo(registered.get(1).url());
            assertThat(storedHooks()).containsExactlyInAnyOrderEntriesOf(Map.of(7L, 100L, 8L, 101L));
            assertThat(currentConfig(1L).gitlabWebhookId()).isNull();
            verify(webhookClient, never()).registerGroupWebhook(anyLong(), anyLong(), any());
            verify(attention, never()).reportUnlessOther(CONNECTION_ID, 1L, Problem.WEBHOOK_UNAVAILABLE, false);
        }

        @Test
        void shouldUseProjectHooksWhenGitLabForbidsGroupHooks() {
            refuseGroupHooks(HttpStatus.FORBIDDEN);
            monitor(7L);

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.registered()).isTrue();
            assertThat(storedHooks()).containsOnlyKeys(7L);
        }

        @Test
        void shouldKeepTheGroupHookPathWhenGitLabListsGroupHooks() {
            fakeGroupHooks();
            monitor(7L);

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.reason()).isEqualTo(WebhookSetupResult.Reason.REGISTERED);
            assertThat(currentConfig(1L).gitlabWebhookId()).isEqualTo(result.webhookId());
            assertThat(currentConfig(1L).projectHooks()).isEmpty();
            verify(webhookClient, never()).registerProjectWebhook(anyLong(), anyLong(), any());
        }

        @Test
        void shouldRegisterOnlyANewlyMonitoredProjectAndNotAskForGroupHooksAgain() {
            monitor(7L);
            webhookService.registerWebhook(workspace);
            String url = registered.getFirst().url();
            monitor(7L, 9L);

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.registered()).isTrue();
            assertThat(registeredOn).containsExactly(7L, 9L);
            assertThat(registered.getLast().url()).isEqualTo(url);
            assertThat(storedHooks()).containsOnlyKeys(7L, 9L);
            verify(webhookClient, times(1)).listGroupWebhooks(1L, 42L);
        }

        /** Each sync registers again; hooks that exist and match cost no provider call. */
        @Test
        void shouldCallNoProviderWhenEveryMonitoredProjectAlreadyHasItsHook() {
            monitor(7L, 8L);
            webhookService.registerWebhook(workspace);
            clearInvocations(webhookClient);

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.registered()).isTrue();
            assertThat(storedHooks()).containsExactlyInAnyOrderEntriesOf(Map.of(7L, 100L, 8L, 101L));
            verifyNoInteractions(webhookClient);
        }

        @Test
        void shouldReleaseTheHookOfAProjectThatIsNoLongerMonitored() {
            monitor(7L, 8L);
            webhookService.registerWebhook(workspace);
            monitor(8L);

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.registered()).isTrue();
            assertThat(released).singleElement().satisfies(resource -> {
                assertThat(resource.type()).isEqualTo(ResourceType.PROJECT_HOOK);
                assertThat(resource.parentId()).isEqualTo("42/7");
                assertThat(resource.resourceId()).isEqualTo("100");
                assertThat(resource.ownershipHash()).isEqualTo(currentConfig(1L).webhookOwnershipHash());
            });
            assertThat(storedHooks()).containsOnlyKeys(8L);
            assertThat(registeredOn).containsExactly(7L, 8L);
        }

        @Test
        void shouldRecordThatGitLabDeliversNothingWhenItRefusesEveryHook() {
            monitor(7L, 8L);
            refusing.addAll(List.of(7L, 8L));

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.registered()).isFalse();
            assertThat(result.reason()).isEqualTo(WebhookSetupResult.Reason.HOOKS_REFUSED);
            assertThat(result.httpStatus()).isEqualTo(403);
            verify(attention).reportUnlessOther(CONNECTION_ID, 1L, Problem.WEBHOOK_UNAVAILABLE, false);
            assertThat(currentConfig(1L).webhooksRefusedAt()).isNotNull();
            // The listing comes first, so a refused project leaves no registration marker to clean up.
            verify(repairs, never()).beginRegistration(anyLong(), anyLong(), any());
            assertThat(currentConfig(1L).projectHooks()).isEmpty();
        }

        @Test
        void shouldHookTheProjectsGitLabAcceptsWhenItRefusesOthers() {
            monitor(7L, 8L);
            refusing.add(8L);

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.reason()).isEqualTo(WebhookSetupResult.Reason.PROJECT_HOOKS_INCOMPLETE);
            assertThat(storedHooks()).containsOnlyKeys(7L);
            verify(attention, never()).reportUnlessOther(CONNECTION_ID, 1L, Problem.WEBHOOK_UNAVAILABLE, false);
        }

        @Test
        void shouldReplaceEveryProjectHookUnderANewRoutingKey() {
            monitor(7L, 8L);
            webhookService.registerWebhook(workspace);
            String oldUrl = registered.getFirst().url();
            GitLabRouteCredential rotated = credential(NEXT_KEY, KEY);

            WebhookSetupResult result = service(rotated).registerWebhook(workspace);

            assertThat(result.registered()).isTrue();
            assertThat(released)
                    .extracting(Resource::parentId, Resource::resourceId)
                    .containsExactlyInAnyOrder(tuple("42/7", "100"), tuple("42/8", "101"));
            assertThat(registered.getLast().url())
                    .startsWith(connectionUrl(rotated) + "?hephaestus_resource=")
                    .isNotEqualTo(oldUrl);
            assertThat(storedHooks()).containsExactlyInAnyOrderEntriesOf(Map.of(7L, 102L, 8L, 103L));
        }

        @Test
        void shouldLeaveProjectHooksToTheServerThatKnowsTheirNewerKey() {
            monitor(7L);
            service(credential(NEXT_KEY, KEY)).registerWebhook(workspace);

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.reason()).isEqualTo(WebhookSetupResult.Reason.ROUTING_KEY_UNKNOWN);
            assertThat(released).isEmpty();
            assertThat(registeredOn).containsExactly(7L);
        }

        @Test
        void shouldReRegisterADeletedProjectHookOnTheHealthCheck() {
            when(workspaceLookup.active()).thenReturn(List.of(workspace));
            monitor(7L, 8L);
            webhookService.registerWebhook(workspace);
            String url = registered.getFirst().url();
            projectHooks.remove(7L);

            webhookService.checkWebhookHealth();

            assertThat(released).extracting(Resource::resourceId).containsExactly("100");
            assertThat(registeredOn).containsExactly(7L, 8L, 7L);
            assertThat(registered.getLast().url()).isEqualTo(url);
            assertThat(storedHooks()).containsExactlyInAnyOrderEntriesOf(Map.of(7L, 102L, 8L, 101L));
        }

        @Test
        void shouldReplaceEveryProjectHookUnderANewMarkerWhenGitLabDisabledOne() {
            when(workspaceLookup.active()).thenReturn(List.of(workspace));
            monitor(7L, 8L);
            webhookService.registerWebhook(workspace);
            String url = registered.getFirst().url();
            projectHooks.put(7L, new ArrayList<>(List.of(new WebhookInfo(100L, url, "disabled"))));

            webhookService.checkWebhookHealth();

            assertThat(released).extracting(Resource::resourceId).containsExactlyInAnyOrder("100", "101");
            assertThat(registeredOn).containsExactly(7L, 8L, 7L, 8L);
            assertThat(registered.getLast().url()).isNotEqualTo(url);
            assertThat(storedHooks()).containsExactlyInAnyOrderEntriesOf(Map.of(7L, 102L, 8L, 103L));
        }

        @Test
        void shouldAskGitLabAgainOnTheHealthCheckAfterItRefusedEveryHook() {
            when(workspaceLookup.active()).thenReturn(List.of(workspace));
            Connection refused = mock(Connection.class);
            when(refused.getId()).thenReturn(CONNECTION_ID);
            when(refused.getConfig()).thenAnswer(inv -> gitLabConfigs.get(1L));
            when(refused.getAttentionProblem()).thenReturn(Problem.WEBHOOK_UNAVAILABLE);
            when(connectionService.findActive(1L, IntegrationKind.GITLAB)).thenReturn(Optional.of(refused));
            bindGitLabConfig(1L, currentConfig(1L).withWebhooksRefusedAt(Instant.parse("2026-11-15T12:00:00Z")));
            monitor(7L);

            webhookService.checkWebhookHealth();

            assertThat(storedHooks()).containsOnlyKeys(7L);
            assertThat(currentConfig(1L).webhooksRefusedAt()).isNull();
            verify(attention).reportUnlessOther(CONNECTION_ID, 1L, Problem.WEBHOOK_UNAVAILABLE, true);
        }

        @Test
        void shouldLeaveTheHooksToTheReconcileThatHoldsTheConnection() {
            when(locks.lock(any())).thenReturn(Optional.empty());
            monitor(7L);

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.reason()).isEqualTo(WebhookSetupResult.Reason.IN_PROGRESS);
            verify(webhookClient, never()).listGroupWebhooks(anyLong(), anyLong());
            verify(webhookClient, never()).registerProjectWebhook(anyLong(), anyLong(), any());
        }

        @Test
        void shouldKeepAStoredGroupHookThatStillExistsWhenTheListingIsRefused() {
            refuseGroupHooks(HttpStatus.FORBIDDEN);
            String url = connectionUrl(routeCredential);
            lenient()
                    .when(webhookClient.getGroupWebhook(1L, 42L, 99L))
                    .thenReturn(Optional.of(new WebhookInfo(99L, url)));
            bindGitLabConfig(1L, new ConnectionConfig.GitLabConfig("https://gitlab.com", 42L, 99L, Set.of(), null));
            monitor(7L);

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.webhookId()).isEqualTo(99L);
            assertThat(currentConfig(1L).gitlabWebhookId()).isEqualTo(99L);
            assertThat(released).isEmpty();
            verify(webhookClient, never()).registerProjectWebhook(anyLong(), anyLong(), any());
        }
    }
}
