package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import de.tum.cit.aet.hephaestus.integration.core.connection.repair.ProviderResourceRepairs;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient.WebhookConfig;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient.WebhookInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.credentials.GitLabTokenLifecycleService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook.GitLabRouteCredential;
import de.tum.cit.aet.hephaestus.observability.ShippedLogEncoder;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
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
    private WorkspaceRepository workspaceRepository;

    @Mock
    private ConnectionService connectionService;

    @Mock
    private ObjectProvider<GitLabTokenLifecycleService> tokenLifecycle;

    @Mock
    private ProviderResourceRepairs repairs;

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
                new PublicApiUrls(AuthPropertiesFixture.withApiBase(URI.create(API_ORIGIN + "/api"), "/api")),
                credential,
                workspaceRepository,
                connectionService,
                repairs,
                tokenLifecycle);
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
        lenient().when(webhookClient.listGroupWebhooks(1L, 42L)).thenAnswer(inv -> List.copyOf(hooks));
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
        void shouldLeaveTheStoredHookAloneWhenItIsThisRoute() {
            storeHook(99L, connectionUrl(routeCredential));

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.webhookId()).isEqualTo(99L);
            assertThat(registered).isEmpty();
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
                    new PublicApiUrls(AuthPropertiesFixture.withApiBase(URI.create(API_ORIGIN), "/api")),
                    new GitLabRouteCredential(unconfigured),
                    workspaceRepository,
                    connectionService,
                    mock(ProviderResourceRepairs.class),
                    tokenLifecycle);

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
            when(workspaceRepository.findByStatus(Workspace.WorkspaceStatus.ACTIVE))
                    .thenReturn(List.of(workspace));
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
        void leavesExecutableWebhookUntouched() {
            storeHook(new WebhookInfo(99L, connectionUrl(routeCredential), "executable"));

            webhookService.checkWebhookHealth();

            verify(webhookClient, never()).registerGroupWebhook(anyLong(), anyLong(), any());
            assertThat(currentConfig(1L).gitlabWebhookId()).isEqualTo(99L);
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
}
