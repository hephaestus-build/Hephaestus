package de.tum.cit.aet.hephaestus.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zaxxer.hikari.HikariDataSource;
import de.tum.cit.aet.hephaestus.Application;
import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceDeliveryAuthorization;
import de.tum.cit.aet.hephaestus.agent.context.CitedSourceAccess;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxDispatcher;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobCompletionService;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunReaper;
import de.tum.cit.aet.hephaestus.core.auth.AccountReferenceService;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.ResearchParticipationQuery;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.MonitoredRepositoryQuery;
import de.tum.cit.aet.hephaestus.integration.core.webhook.ProviderEventReceiver;
import de.tum.cit.aet.hephaestus.integration.core.webhook.WebhookPayloadSizeFilter;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmRepositoryItemLifecycle;
import de.tum.cit.aet.hephaestus.integration.scm.github.lifecycle.GitHubLifecycleListener;
import de.tum.cit.aet.hephaestus.integration.scm.github.workspace.GitHubInstallationReconciler;
import de.tum.cit.aet.hephaestus.integration.scm.github.workspace.GitHubWorkspaceProvisioningAdapter;
import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackConsentBlocks;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackParticipantConsent;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackParticipantConsentRepository;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackSignatureVerifier;
import de.tum.cit.aet.hephaestus.integration.slack.interactivity.SlackInteractivityController;
import de.tum.cit.aet.hephaestus.integration.slack.interactivity.SlackInteractivityHandler;
import de.tum.cit.aet.hephaestus.integration.slack.interactivity.SlackInteractivityRawBodyFilter;
import de.tum.cit.aet.hephaestus.integration.slack.retention.SlackRetentionPurger;
import de.tum.cit.aet.hephaestus.integration.slack.retention.SlackWorkspaceContentEraser;
import de.tum.cit.aet.hephaestus.integration.slack.retention.SlackWorkspacePurgeAdapter;
import de.tum.cit.aet.hephaestus.integration.slack.webhook.SlackWebhookSignatureVerifier;
import de.tum.cit.aet.hephaestus.practices.result.ResultAdmissionPolicy;
import de.tum.cit.aet.hephaestus.practices.result.ResultMarkService;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewCoverageService;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStart;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipService;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import jakarta.servlet.Filter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.BootstrapUtils;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Refreshes the complete application graph without running image pulls or opening worker sessions. */
@Tag("integration")
@ContextConfiguration(classes = Application.class)
class RuntimeRoleContextIntegrationTest {
    private static final String SLACK_INTERACTIVITY = "/webhooks/slack/interactivity";

    /** The processes a release runs: the server, with or without agent jobs. */
    enum Deployment {
        SERVER(false),
        SERVER_WITH_AGENTS(true);

        final boolean agents;

        Deployment(boolean agents) {
            this.agents = agents;
        }
    }

    @ParameterizedTest
    @EnumSource(Deployment.class)
    void shouldResolveEveryApplicationBeanForEachDeployment(Deployment process) {
        boolean agents = process.agents;
        var database = PostgreSQLTestContainer.createMigratedDatabase(
                "runtime_role_" + process.name().toLowerCase(Locale.ROOT));
        int maximum = 16;
        var configuration = BootstrapUtils.resolveTestContextBootstrapper(
                        agents ? AgentRuntimeWithoutCluster.class : getClass())
                .buildMergedContextConfiguration();
        var runner = new WebApplicationContextRunner()
                .withInitializer(application -> {
                    var conversion = new ApplicationConversionService();
                    application.getEnvironment().setConversionService(conversion);
                    application.getBeanFactory().setConversionService(conversion);
                })
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withInitializer(application -> application
                        .getEnvironment()
                        .getPropertySources()
                        .addFirst(new SystemEnvironmentPropertySource(
                                "chart-role-pool",
                                Map.of(
                                        "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE",
                                        Integer.toString(maximum),
                                        "SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE",
                                        "2"))))
                .withInitializer(application -> configuration
                        .getContextCustomizers()
                        .forEach(customizer -> customizer.customizeContext(application, configuration)))
                .withUserConfiguration(Application.class)
                .withPropertyValues(
                        "spring.profiles.active=default",
                        "spring.config.import=classpath:application-test.yml",
                        "spring.main.allow-bean-definition-overriding=false",
                        "spring.main.lazy-initialization=false",
                        "spring.datasource.url=" + database.jdbcUrl(),
                        "spring.datasource.username=" + database.username(),
                        "spring.datasource.password=" + database.password(),
                        "hephaestus.agent.enabled=" + agents,
                        "hephaestus.integration.slack.enabled=true",
                        "hephaestus.integration.outline.enabled=true",
                        RuntimeRole.SERVER_PROPERTY + "=true");
        if (agents) {
            // Resolve the real agent runtime graph; this test has no cluster and starts no sandbox.
            runner = runner.withPropertyValues("hephaestus.sandbox.kubernetes.namespace=role-test-sandboxes");
        }
        runner.run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(RuntimeRole.enabled(application.getEnvironment())).containsExactly(RuntimeRole.SERVER);
            assertThat(application).hasSingleBean(ScmRepositoryItemLifecycle.class);
            assertThat(application).hasSingleBean(AgentRunReaper.class);
            assertThat(application).hasSingleBean(ReviewJobCompletionService.class);
            assertThat(application.getBeansWithAnnotation(WorkspaceScopedController.class))
                    .isNotEmpty();
            assertThat(application).hasSingleBean(AccountReferenceService.class);
            assertThat(application).hasSingleBean(ResearchParticipationQuery.class);
            assertThat(application).hasSingleBean(MonitoredRepositoryQuery.class);
            assertThat(application).hasSingleBean(PracticeReviewCoverageService.class);
            assertThat(application).hasSingleBean(ResultAdmissionPolicy.class);
            assertThat(application).hasSingleBean(GitHubLifecycleListener.class);
            assertThat(application).hasSingleBean(GitHubInstallationReconciler.class);
            assertThat(application).hasSingleBean(GitHubWorkspaceProvisioningAdapter.class);
            assertThat(application).hasSingleBean(SlackRetentionPurger.class);
            assertThat(application).hasSingleBean(SlackWorkspaceContentEraser.class);
            assertThat(application).hasSingleBean(SlackWorkspacePurgeAdapter.class);
            if (agents) {
                assertThat(application).hasSingleBean(ResultAdmissionPolicy.class);
                assertThat(application).hasSingleBean(SandboxDispatcher.class);
            } else {
                assertThat(application).doesNotHaveBean(SandboxDispatcher.class);
            }
            assertThat(application).hasSingleBean(ProviderEventReceiver.class);
            assertThat(application).hasSingleBean(ResultMarkService.class);
            assertThat(application).hasSingleBean(CitedSourceAccess.class);
            assertThat(application).hasSingleBean(EvidenceDeliveryAuthorization.class);
            // A claim replaces a queued review through the review's own start service, with the real ledger.
            assertThat(application).hasSingleBean(ReviewStart.class);
            assertDatabaseConnections(application, database, maximum);
            assertSlackIngress(application);
        });
    }

    /** Count live connections while the server holds its entire configured pool. */
    private static void assertDatabaseConnections(
            AssertableWebApplicationContext application, PostgreSQLTestContainer.TestDatabase database, int maximum)
            throws SQLException {
        var borrowed = new ArrayList<java.sql.Connection>();
        try {
            var pool = application.getBean(HikariDataSource.class);
            assertThat(pool.getMaximumPoolSize()).isEqualTo(maximum);
            assertThat(pool.getMinimumIdle()).isEqualTo(2);
            for (int i = 0; i < maximum; i++) {
                borrowed.add(pool.getConnection());
            }
            try (var observer =
                            DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                    var statement = observer.createStatement();
                    var result = statement.executeQuery("SELECT count(*) FROM pg_stat_activity "
                            + "WHERE datname=current_database() AND pid<>pg_backend_pid()")) {
                assertThat(result.next()).isTrue();
                // The lazy ledger listener and the evidence erasure listener are the dedicated connections outside
                // Hikari.
                assertThat(result.getInt(1)).isBetween(maximum, maximum + 2);
            }
        } finally {
            for (var connection : borrowed) {
                connection.close();
            }
        }
    }

    /**
     * The server receives Slack Events and publishes them, and answers interactivity, committing a button's consent
     * change before it answers Slack.
     */
    private static void assertSlackIngress(AssertableWebApplicationContext application) throws Exception {
        assertThat(application).hasSingleBean(SlackSignatureVerifier.class);
        assertThat(application).hasSingleBean(SlackWebhookSignatureVerifier.class);
        assertThat(application).hasSingleBean(SlackInteractivityController.class);
        assertThat(application).hasSingleBean(SlackInteractivityHandler.class);
        assertSignedInteractivityIsAnswered(application);
    }

    /**
     * A button press through what the server registers for the path, in the servlet order: the payload
     * bound, the raw-body cache, the security chain, then the controller's signature check and the real handler. The
     * press names a team no workspace is connected to, so the handler looks it up in the migrated database, finds
     * nothing to change and the request is answered 200.
     */
    private static void assertSignedInteractivityIsAnswered(AssertableWebApplicationContext application)
            throws Exception {
        List<Filter> guards = Arrays.stream(application.getBeanNamesForType(FilterRegistrationBean.class))
                .<FilterRegistrationBean<?>>map(name -> (FilterRegistrationBean<?>) application.getBean(name))
                .filter(registration -> registration.getUrlPatterns().contains(SLACK_INTERACTIVITY)
                        || registration.getUrlPatterns().contains("/webhooks/*"))
                .sorted(Comparator.comparingInt(registration -> registration.getOrder()))
                .map(registration -> Objects.requireNonNull(registration.getFilter()))
                .toList();
        assertThat(guards)
                .<Class<?>>extracting(Object::getClass)
                .containsExactly(WebhookPayloadSizeFilter.class, SlackInteractivityRawBodyFilter.class);
        var builder = MockMvcBuilders.webAppContextSetup(application);
        guards.forEach(filter -> builder.addFilter(filter, SLACK_INTERACTIVITY));
        MockMvc mvc = builder.addFilter(application.getBean("springSecurityFilterChain", Filter.class))
                .build();

        String secret = application.getEnvironment().getRequiredProperty("hephaestus.integration.slack.signing-secret");
        byte[] body = ("payload="
                        + URLEncoder.encode(
                                "{\"type\":\"block_actions\",\"team\":{\"id\":\"T0ROLECONTEXT\"},"
                                        + "\"user\":{\"id\":\"U0ROLECONTEXT\"},\"actions\":[]}",
                                StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(Instant.now().getEpochSecond());

        mvc.perform(post(SLACK_INTERACTIVITY)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Slack-Request-Timestamp", timestamp)
                        .header("X-Slack-Signature", slackSignature(secret, timestamp, body))
                        .content(body))
                .andExpect(status().isOk());
        mvc.perform(post(SLACK_INTERACTIVITY)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Slack-Request-Timestamp", timestamp)
                        .header("X-Slack-Signature", slackSignature("not-the-signing-secret", timestamp, body))
                        .content(body))
                .andExpect(status().isUnauthorized());
        // Without a Content-Length the payload bound refuses the body before anything buffers it.
        mvc.perform(post(SLACK_INTERACTIVITY)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked"))
                .andExpect(status().isLengthRequired());

        assertSignedConsentCommitsBeforeAcknowledgement(application, mvc, secret);
    }

    private static void assertSignedConsentCommitsBeforeAcknowledgement(
            AssertableWebApplicationContext application, MockMvc mvc, String secret) throws Exception {
        var workspaces = application.getBean(WorkspaceRepository.class);
        Workspace workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("role-slack-consent"));
        Workspace sibling = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("role-slack-sibling"));
        String team = "TROLECONSENT";
        String actor = "UROLEACTOR";
        String otherMember = "UROLEOTHER";
        var connection = new Connection(
                workspace, IntegrationKind.SLACK, team, new ConnectionConfig.SlackConfig(team, null, null, Set.of()));
        connection.setState(IntegrationState.ACTIVE);
        application.getBean(ConnectionRepository.class).saveAndFlush(connection);
        linkSlackMember(application, workspace, team, actor);
        linkSlackMember(application, workspace, team, otherMember);

        var consents = application.getBean(SlackParticipantConsentRepository.class);
        var actorKey = new SlackParticipantConsent.Id(workspace.getId(), actor);
        var otherKey = new SlackParticipantConsent.Id(workspace.getId(), otherMember);
        var siblingKey = new SlackParticipantConsent.Id(sibling.getId(), actor);
        assertThat(consents.findById(actorKey)).isEmpty();
        assertThat(consents.findById(otherKey)).isEmpty();
        assertThat(consents.findById(siblingKey)).isEmpty();

        // A button value cannot redirect a person's consent decision to another member. A blank channel
        // keeps the real handler's optional outbound confirmation off this HTTP/commit proof.
        String payload = "{\"type\":\"block_actions\",\"team\":{\"id\":\"" + team
                + "\"},\"user\":{\"id\":\"" + actor + "\"},\"actions\":[{\"action_id\":\""
                + SlackConsentBlocks.ACTION_PARTICIPANT_OPT_OUT + "\",\"value\":\"" + otherMember + "\"}]}";
        byte[] body =
                ("payload=" + URLEncoder.encode(payload, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        mvc.perform(post(SLACK_INTERACTIVITY)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Slack-Request-Timestamp", timestamp)
                        .header("X-Slack-Signature", slackSignature("not-the-signing-secret", timestamp, body))
                        .content(body))
                .andExpect(status().isUnauthorized());
        assertThat(consents.findById(actorKey)).isEmpty();
        assertThat(consents.findById(otherKey)).isEmpty();

        mvc.perform(post(SLACK_INTERACTIVITY)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Slack-Request-Timestamp", timestamp)
                        .header("X-Slack-Signature", slackSignature(secret, timestamp, body))
                        .content(body))
                .andExpect(status().isOk());
        // No outer test transaction or polling: this read follows the completed HTTP acknowledgement.
        SlackParticipantConsent recorded = consents.findById(actorKey).orElseThrow();
        assertThat(recorded.isIngestionOptedOut()).isTrue();
        assertThat(consents.findById(otherKey)).isEmpty();
        assertThat(consents.findById(siblingKey)).isEmpty();
    }

    private static void linkSlackMember(
            AssertableWebApplicationContext application, Workspace workspace, String team, String slackUserId) {
        var accounts = application.getBean(AccountRepository.class);
        var account = accounts.saveAndFlush(new Account("role-consent-" + slackUserId));
        long accountId = Objects.requireNonNull(account.getId());
        application
                .getBean(MembershipService.class)
                .createMembership(workspace, accountId, Membership.WorkspaceRole.MEMBER);
        var provider = application
                .getBean(IdentityProviderRepository.class)
                .findByTypeAndServerUrl(IdentityProviderType.SLACK, "https://slack.com")
                .orElseThrow();
        var identity = new Identity();
        identity.setAccount(account);
        identity.setProviderId(Objects.requireNonNull(provider.getId()));
        identity.setTeamKey(team);
        identity.setProviderUserKey(slackUserId);
        application.getBean(IdentityRepository.class).saveAndFlush(identity);
    }

    private static String slackSignature(String secret, String timestamp, byte[] body) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(("v0:" + timestamp + ":").getBytes(StandardCharsets.UTF_8));
        return "v0=" + HexFormat.of().formatHex(mac.doFinal(body));
    }
}
