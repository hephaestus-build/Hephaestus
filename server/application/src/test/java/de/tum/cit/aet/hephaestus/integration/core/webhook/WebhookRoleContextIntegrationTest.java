package de.tum.cit.aet.hephaestus.integration.core.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.sandbox.AgentImageReferenceGuard;
import de.tum.cit.aet.hephaestus.integration.outline.webhook.OutlineRouteCredential;
import de.tum.cit.aet.hephaestus.integration.outline.webhook.OutlineWebhookController;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook.GitLabConnectionWebhookController;
import de.tum.cit.aet.hephaestus.integration.slack.webhook.SlackChannelMessagePublishGate;
import de.tum.cit.aet.hephaestus.testconfig.NatsTestContainer;
import io.nats.client.Connection;
import io.nats.client.JetStreamManagement;
import io.nats.client.Nats;
import io.nats.client.impl.Headers;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.data.repository.Repository;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.TransactionManager;

/**
 * The webhook role as a deployment starts it, with PostgreSQL unreachable: its context holds no database, and it
 * still verifies and publishes deliveries of every provider (ADR 0066). The only service it reaches is NATS.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            // Nothing listens on port 1: a bean that opened a connection would fail the start.
            "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/unreachable",
            "hephaestus.sync.nats.enabled=true",
            "hephaestus.integration.outline.enabled=true"
        })
@ActiveProfiles({"test", "webhook"})
@Tag("integration")
class WebhookRoleContextIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static Connection nats;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private OutlineRouteCredential outlineRoutes;

    @LocalServerPort
    private int port;

    @Value("${hephaestus.webhook.secret}")
    private String githubSecret;

    @Value("${hephaestus.integration.slack.signing-secret}")
    private String slackSecret;

    @DynamicPropertySource
    static void broker(DynamicPropertyRegistry registry) {
        registry.add("hephaestus.sync.nats.server", NatsTestContainer::getServerUrl);
    }

    @BeforeAll
    static void connect() throws Exception {
        nats = Nats.connect(NatsTestContainer.getServerUrl());
    }

    @AfterAll
    static void disconnect() throws InterruptedException {
        nats.close();
    }

    @Test
    void shouldHoldNoDatabaseWhenOnlyTheWebhookRoleRuns() {
        List<String> persistence = Arrays.stream(context.getBeanDefinitionNames())
                .filter(name -> isPersistence(context.getType(name)))
                .map(name -> name + ": " + context.getType(name))
                .toList();
        assertThat(persistence)
                .as("beans of a persistence type in the webhook role")
                .isEmpty();
        assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();

        assertThat(context.getBeanNamesForType(WebhookController.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(GitLabConnectionWebhookController.class))
                .hasSize(1);
        assertThat(context.getBeanNamesForType(OutlineWebhookController.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(SlackChannelMessagePublishGate.class))
                .hasSize(1);
        // A reference the workers cannot run stops this role too (ADR 0031).
        assertThat(context.getBeanNamesForType(AgentImageReferenceGuard.class)).hasSize(1);
    }

    @Test
    void shouldPublishASignedGitHubDeliveryWhenPostgreSqlIsUnreachable() throws Exception {
        String delivery = UUID.randomUUID().toString();
        String repository = "r" + Long.toHexString(System.nanoTime());
        byte[] body = ("{\"ref\":\"refs/heads/main\",\"repository\":{\"name\":\"" + repository
                        + "\",\"full_name\":\"octo/" + repository + "\",\"owner\":{\"login\":\"octo\"}}}")
                .getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> response = post(
                "/webhooks/github",
                body,
                Map.of(
                        "X-GitHub-Event",
                        "push",
                        "X-GitHub-Delivery",
                        delivery,
                        "X-Hub-Signature-256",
                        "sha256=" + hmac(githubSecret.getBytes(StandardCharsets.UTF_8), body)));

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(lastHeader("github", "github.octo." + repository + ".push", "X-GitHub-Delivery"))
                .isEqualTo(delivery);
    }

    @Test
    void shouldPublishAnOutlineDeliveryOnItsRouteWhenPostgreSqlIsUnreachable() throws Exception {
        OutlineRouteCredential.Issued route = outlineRoutes.issue();
        // The server projects the routes of the ACTIVE connections.
        new ProjectedKeys(nats, OutlineWebhookController.ROUTES_BUCKET)
                .add(OutlineWebhookController.routeKey(route.route()));
        String subscription = UUID.randomUUID().toString();
        byte[] body = ("{\"id\":\"" + UUID.randomUUID() + "\",\"webhookSubscriptionId\":\"" + subscription + "\","
                        + "\"event\":\"documents.update\",\"payload\":{\"id\":\"doc-1\"}}")
                .getBytes(StandardCharsets.UTF_8);
        long millis = Instant.now().toEpochMilli();
        String signature = hmac(route.signingSecret().getBytes(StandardCharsets.UTF_8), millis + ".", body);

        HttpResponse<String> response = post(
                OutlineWebhookController.PATH_PREFIX + route.route(),
                body,
                Map.of("Outline-Signature", "t=" + millis + ",s=" + signature));

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(lastHeader(
                        "outline",
                        "outline." + subscription + ".documents~update",
                        OutlineWebhookController.HEADER_ROUTE))
                .isEqualTo(route.route());
    }

    @Test
    void shouldRefuseAnOutlineDeliveryOnARouteNoConnectionHolds() throws Exception {
        // Signed correctly, but no ACTIVE connection holds the route: Outline disables a subscription that gets 410.
        OutlineRouteCredential.Issued orphan = outlineRoutes.issue();
        new ProjectedKeys(nats, OutlineWebhookController.ROUTES_BUCKET).add("someOtherRoute");
        byte[] body = "{\"event\":\"documents.update\"}".getBytes(StandardCharsets.UTF_8);
        long millis = Instant.now().toEpochMilli();
        String signature = hmac(orphan.signingSecret().getBytes(StandardCharsets.UTF_8), millis + ".", body);

        HttpResponse<String> response = post(
                OutlineWebhookController.PATH_PREFIX + orphan.route(),
                body,
                Map.of("Outline-Signature", "t=" + millis + ",s=" + signature));

        assertThat(response.statusCode()).isEqualTo(410);
    }

    @Test
    void shouldRejectAnOutlineDeliveryWhenItIsSignedForAnotherRoute() throws Exception {
        OutlineRouteCredential.Issued signedFor = outlineRoutes.issue();
        OutlineRouteCredential.Issued postedTo = outlineRoutes.issue();
        byte[] body = "{\"event\":\"documents.update\"}".getBytes(StandardCharsets.UTF_8);
        long millis = Instant.now().toEpochMilli();
        String signature = hmac(signedFor.signingSecret().getBytes(StandardCharsets.UTF_8), millis + ".", body);

        HttpResponse<String> response = post(
                OutlineWebhookController.PATH_PREFIX + postedTo.route(),
                body,
                Map.of("Outline-Signature", "t=" + millis + ",s=" + signature));

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void shouldPublishOnlyTheSlackChannelMessagesWhoseConsentTheServerProjected() throws Exception {
        new ProjectedKeys(nats, SlackChannelMessagePublishGate.CONSENT_BUCKET).add("T1.CACTIVE");

        assertThat(postSlackChannelMessage("CACTIVE").body()).contains("\"ok\"");
        assertThat(postSlackChannelMessage("CPAUSED").body()).contains("\"dropped\"");
    }

    /** A bean that can reach a database: a connection, an entity manager, a repository, a template or a migrator. */
    private static boolean isPersistence(@Nullable Class<?> type) {
        return type != null
                && (Stream.of(
                                        DataSource.class,
                                        EntityManagerFactory.class,
                                        EntityManager.class,
                                        Repository.class,
                                        JdbcOperations.class,
                                        TransactionManager.class,
                                        SpringLiquibase.class)
                                .anyMatch(database -> database.isAssignableFrom(type))
                        || type.isAnnotationPresent(Entity.class));
    }

    private HttpResponse<String> postSlackChannelMessage(String channel) throws Exception {
        byte[] body = ("{\"type\":\"event_callback\",\"team_id\":\"T1\",\"event_id\":\"Ev" + UUID.randomUUID()
                        + "\",\"event\":{\"type\":\"message\",\"channel_type\":\"channel\",\"channel\":\"" + channel
                        + "\",\"text\":\"hello\",\"ts\":\"1.000001\"}}")
                .getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String signature = hmac(slackSecret.getBytes(StandardCharsets.UTF_8), "v0:" + timestamp + ":", body);
        HttpResponse<String> response = post(
                "/webhooks/slack",
                body,
                Map.of("X-Slack-Request-Timestamp", timestamp, "X-Slack-Signature", "v0=" + signature));
        assertThat(response.statusCode()).isEqualTo(202);
        return response;
    }

    private HttpResponse<String> post(String path, byte[] body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(request::header);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A header of the newest message on {@code subject}, which only this test publishes to. */
    private static @Nullable String lastHeader(String stream, String subject, String name) throws Exception {
        JetStreamManagement management = nats.jetStreamManagement();
        Headers headers = management.getLastMessage(stream, subject).getHeaders();
        return headers == null ? null : headers.getFirst(name);
    }

    private static String hmac(byte[] secret, byte[] body) throws Exception {
        return hmac(secret, "", body);
    }

    private static String hmac(byte[] secret, String prefix, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        mac.update(prefix.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }
}
