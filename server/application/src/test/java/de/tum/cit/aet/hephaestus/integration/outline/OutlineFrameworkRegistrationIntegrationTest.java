package de.tum.cit.aet.hephaestus.integration.outline;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationLifecycleListener;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationManifest;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.outline.webhook.OutlineRouteCredential;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook.GitLabRouteCredential;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.HeldTransaction;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * With the integration enabled, Outline becomes a registered kind — which means
 * {@code IntegrationFrameworkBootstrap} demands an {@link ApiCredentialProvider} and an
 * {@link IntegrationLifecycleListener} for it or the context fails to start. This test booting at all
 * is the proof those beans land together; the assertions pin the registration.
 */
@TestPropertySource(properties = "hephaestus.integration.outline.enabled=true")
class OutlineFrameworkRegistrationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private List<IntegrationManifest> manifests;

    @Autowired
    private List<ApiCredentialProvider> credentialProviders;

    @Autowired
    private List<IntegrationLifecycleListener> lifecycleListeners;

    @Test
    void outlineIsRegisteredWithTheRequiredFrameworkBeans() {
        assertThat(manifests).anyMatch(m -> m.kind() == IntegrationKind.OUTLINE);
        assertThat(credentialProviders).anyMatch(p -> p.kind() == IntegrationKind.OUTLINE);
        assertThat(lifecycleListeners).anyMatch(l -> l.kind() == IntegrationKind.OUTLINE);
    }

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private WebTestClient http;

    @Autowired
    private WebhookProperties webhookProperties;

    @Autowired
    private GitLabRouteCredential gitlabRoutes;

    @Autowired
    private OutlineRouteCredential outlineRoutes;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    static Stream<Arguments> inactiveConnections() {
        return Arrays.stream(IntegrationKind.values())
                .flatMap(kind -> Stream.of(
                                IntegrationState.PENDING, IntegrationState.SUSPENDED, IntegrationState.UNINSTALLED)
                        .map(state -> Arguments.of(kind, state)));
    }

    @ParameterizedTest
    @MethodSource("inactiveConnections")
    void signedWorkEventStoresNoPayloadAndRecordsWhyUntilItsConnectionIsActive(
            IntegrationKind kind, IntegrationState state) throws Exception {
        var workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace("admission-" + UUID.randomUUID()));
        workspace.setAccountLogin("gate-" + workspace.getId());
        workspace = workspaces.save(workspace);
        long workspaceId = workspace.getId();
        var outlineRoute = outlineRoutes.issue();
        String subscriptionId = UUID.randomUUID().toString();
        String team = "TGATE" + workspaceId;
        ConnectionConfig config = switch (kind) {
            case GITHUB ->
                new ConnectionConfig.GitHubAppConfig(
                        workspaceId, workspace.getAccountLogin(), "https://github.com", Set.of());
            case GITLAB -> new ConnectionConfig.GitLabConfig("https://gitlab.example", 42L, null, Set.of(), null);
            case SLACK -> new ConnectionConfig.SlackConfig(team, null, null, Set.of());
            case OUTLINE ->
                new ConnectionConfig.OutlineConfig(
                        "https://outline.example", subscriptionId, outlineRoute.route(), Set.of());
        };
        String instanceKey = switch (kind) {
            case GITHUB -> Long.toString(workspaceId);
            case GITLAB -> "https://gitlab.example";
            case SLACK -> team;
            case OUTLINE -> "https://outline.example";
        };
        var connection = new Connection(workspace, kind, instanceKey, config);
        connection.setState(state);
        connection = connections.saveAndFlush(connection);
        long connectionId = Objects.requireNonNull(connection.getId());
        Long unrelatedConnectionId = null;
        if (kind == IntegrationKind.GITHUB) {
            var monitor = new RepositoryToMonitor();
            monitor.setWorkspace(workspace);
            monitor.setNativeId(workspaceId);
            monitor.setNameWithOwner(workspace.getAccountLogin() + "/app");
            monitors.saveAndFlush(monitor);
            // An unrelated active installation cannot admit a delivery of this inactive installation.
            var other = new Connection(
                    workspace,
                    IntegrationKind.GITHUB,
                    Long.toString(workspaceId + 1000000),
                    new ConnectionConfig.GitHubAppConfig(
                            workspaceId + 1000000, workspace.getAccountLogin(), "https://github.com", Set.of()));
            other.setState(IntegrationState.ACTIVE);
            unrelatedConnectionId = connections.saveAndFlush(other).getId();
        }
        String delivery = UUID.randomUUID().toString();
        String body = switch (kind) {
            case GITHUB -> """
                    {"action":"opened","installation":{"id":%s},"repository":{"name":"app","owner":{"login":"%s"}},"private":"%s"}
                    """.formatted(workspaceId, workspace.getAccountLogin(), delivery);
            case GITLAB -> """
                    {"object_kind":"push","private":"%s"}
                    """.formatted(delivery);
            case SLACK -> """
                    {"type":"event_callback","team_id":"%s","event_id":"%s",
                     "event":{"type":"app_mention","channel":"C1","text":"private work"}}
                    """.formatted(team, delivery);
            case OUTLINE -> """
                    {"id":"%s","webhookSubscriptionId":"%s","event":"documents.update",
                     "payload":{"id":"private-document","text":"private work"}}
                    """.formatted(delivery, subscriptionId);
        };
        String path;
        Map<String, String> headers;
        switch (kind) {
            case GITHUB -> {
                path = "/webhooks/github";
                headers = Map.of(
                        "X-GitHub-Event",
                        "issues",
                        "X-GitHub-Delivery",
                        delivery,
                        "Hephaestus-GitHub-Installation-Id",
                        Long.toString(workspaceId + 1000000),
                        "X-Hub-Signature-256",
                        "sha256=" + hmac(Objects.requireNonNull(webhookProperties.secret()), "", body));
            }
            case GITLAB -> {
                var issued = gitlabRoutes.issue(new GitLabRouteCredential.Route(
                        connectionId, workspaceId, "https://gitlab.example", 42L, workspace.getAccountLogin()));
                path = "/webhooks/gitlab/connections/" + connectionId + "/" + issued.keyId() + "/" + issued.routeId();
                headers = Map.of(
                        "X-Gitlab-Token",
                        issued.token(),
                        "X-Gitlab-Event",
                        "Push Hook",
                        "X-Gitlab-Webhook-UUID",
                        delivery);
            }
            case SLACK -> {
                path = "/webhooks/slack";
                String time = Long.toString(Instant.now().getEpochSecond());
                headers = Map.of(
                        "X-Slack-Request-Timestamp",
                        time,
                        "X-Slack-Signature",
                        "v0=" + hmac("test-slack-signing-secret-not-real", "v0:" + time + ":", body));
            }
            case OUTLINE -> {
                path = "/webhooks/outline/" + outlineRoute.route();
                String time = Long.toString(Instant.now().toEpochMilli());
                headers = Map.of(
                        "Outline-Signature",
                        "t=" + time + ",s=" + hmac(outlineRoute.signingSecret(), time + ".", body));
            }
            default -> throw new IllegalArgumentException("No webhook adapter for " + kind);
        }
        var refusal = meters.counter(
                IntegrationCoreMetrics.WEBHOOK_REJECTED,
                "provider",
                kind.name().toLowerCase(Locale.ROOT),
                "reason",
                "connection-not-active");
        double before = refusal.count();

        var unsigned = new LinkedHashMap<>(headers);
        unsigned.remove(
                switch (kind) {
                    case GITHUB -> "X-Hub-Signature-256";
                    case GITLAB -> "X-Gitlab-Token";
                    case SLACK -> "X-Slack-Signature";
                    case OUTLINE -> "Outline-Signature";
                });
        post(path, unsigned, body).expectStatus().isUnauthorized().expectBody(Void.class);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM provider_event WHERE workspace_id = ?", Integer.class, workspaceId))
                .isZero();
        assertThat(refusal.count())
                .as("an unauthenticated request never reaches lifecycle admission")
                .isEqualTo(before);

        signedPost(path, headers, body);

        assertThat(refusal.count()).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM provider_event WHERE workspace_id = ?", Integer.class, workspaceId))
                .isZero();

        if (kind == IntegrationKind.GITHUB) {
            var retagged = new LinkedHashMap<>(headers);
            retagged.put("X-GitHub-Event", "installation");
            var invalidRoute = meters.counter(
                    IntegrationCoreMetrics.WEBHOOK_REJECTED, "provider", "github", "reason", "invalid-event-route");
            double invalidBefore = invalidRoute.count();
            post(path, retagged, body).expectStatus().isBadRequest().expectBody(Void.class);
            assertThat(invalidRoute.count()).isEqualTo(invalidBefore + 1);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM provider_event WHERE delivery_key = ?",
                            Integer.class,
                            "github-" + delivery))
                    .isZero();
        }
        Long otherConnectionId = unrelatedConnectionId;
        transactions.executeWithoutResult(status -> {
            if (otherConnectionId != null) {
                connections.acquireLifecycleLock(otherConnectionId, workspaceId);
                var other = connections
                        .findByIdAndWorkspaceId(otherConnectionId, workspaceId)
                        .orElseThrow();
                other.setState(IntegrationState.SUSPENDED);
                connections.saveAndFlush(other);
            }
            connections.acquireLifecycleLock(connectionId, workspaceId);
            var resumed = connections
                    .findByIdAndWorkspaceId(connectionId, workspaceId)
                    .orElseThrow();
            resumed.setState(IntegrationState.ACTIVE);
            connections.saveAndFlush(resumed);
        });
        // The same signed delivery must be accepted after resume: rejection created no dedupe or payload row.
        signedPost(path, headers, body);
        assertThat(jdbc.queryForList(
                        "SELECT payload FROM provider_event WHERE workspace_id = ?", byte[].class, workspaceId))
                .singleElement()
                .isEqualTo(body.getBytes(StandardCharsets.UTF_8));
        signedPost(path, headers, body);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM provider_event WHERE workspace_id = ?", Integer.class, workspaceId))
                .as("a duplicate signed delivery stores no second payload")
                .isEqualTo(1);
        if (kind == IntegrationKind.OUTLINE) {
            var foreign = outlineRoutes.issue();
            String changed = body.replace(delivery, UUID.randomUUID().toString());
            String time = Long.toString(Instant.now().toEpochMilli());
            var mismatch = meters.counter(
                    IntegrationCoreMetrics.WEBHOOK_REJECTED,
                    "provider",
                    "outline",
                    "reason",
                    "connection-route-mismatch");
            double mismatches = mismatch.count();

            signedPost(
                    "/webhooks/outline/" + foreign.route(),
                    Map.of(
                            "Outline-Signature",
                            "t=" + time + ",s=" + hmac(foreign.signingSecret(), time + ".", changed)),
                    changed);

            assertThat(mismatch.count()).isEqualTo(mismatches + 1);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM provider_event WHERE workspace_id = ?", Integer.class, workspaceId))
                    .isEqualTo(1);
        }
    }

    @Test
    void shouldReturnRetryableFailureWithoutPayloadWhenTheOutlineRouteChangesDuringAdmission() throws Exception {
        var workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace("route-race-" + UUID.randomUUID()));
        long workspaceId = workspace.getId();
        var originalRoute = outlineRoutes.issue();
        var replacementRoute = outlineRoutes.issue();
        String subscription = UUID.randomUUID().toString();
        var connection = new Connection(
                workspace,
                IntegrationKind.OUTLINE,
                "https://outline.example",
                new ConnectionConfig.OutlineConfig(
                        "https://outline.example", subscription, originalRoute.route(), Set.of()));
        connection.setState(IntegrationState.ACTIVE);
        long id = Objects.requireNonNull(connections.saveAndFlush(connection).getId());
        String body = """
                {"id":"%s","webhookSubscriptionId":"%s","event":"documents.update",
                 "payload":{"id":"private-document","text":"private work"}}
                """.formatted(UUID.randomUUID(), subscription);
        String time = Long.toString(Instant.now().toEpochMilli());
        var headers = Map.of(
                "Outline-Signature", "t=" + time + ",s=" + hmac(originalRoute.signingSecret(), time + ".", body));
        HeldTransaction changing = HeldTransaction.holdIn(transactions, jdbc, () -> {
            connections.acquireLifecycleLock(id, workspaceId);
            var current = connections.findByIdAndWorkspaceId(id, workspaceId).orElseThrow();
            current.setConfig(new ConnectionConfig.OutlineConfig(
                    "https://outline.example", subscription, replacementRoute.route(), Set.of()));
            connections.saveAndFlush(current);
        });
        var receiving =
                CompletableFuture.runAsync(() -> post("/webhooks/outline/" + originalRoute.route(), headers, body)
                        .expectStatus()
                        .isEqualTo(503)
                        .expectBody()
                        .jsonPath("$.detail")
                        .isEqualTo("store-failed"));
        changing.commitAfter(receiving);
        receiving.get(10, TimeUnit.SECONDS);

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM provider_event WHERE workspace_id = ?", Integer.class, workspaceId))
                .isZero();
    }

    private void signedPost(String path, Map<String, String> headers, String body) {
        post(path, headers, body).expectStatus().isAccepted().expectBody(Void.class);
    }

    private WebTestClient.ResponseSpec post(String path, Map<String, String> headers, String body) {
        return http.post()
                .uri(path)
                .headers(values -> headers.forEach(values::set))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body.getBytes(StandardCharsets.UTF_8))
                .exchange();
    }

    private static String hmac(String secret, String prefix, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(prefix.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
