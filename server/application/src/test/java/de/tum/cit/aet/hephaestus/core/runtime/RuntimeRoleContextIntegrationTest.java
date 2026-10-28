package de.tum.cit.aet.hephaestus.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.tum.cit.aet.hephaestus.Application;
import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceDeliveryAuthorization;
import de.tum.cit.aet.hephaestus.agent.context.CitedSourceAccess;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobExecutor;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerDrainCoordinator;
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
import de.tum.cit.aet.hephaestus.integration.core.webhook.JetStreamPublisher;
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
import de.tum.cit.aet.hephaestus.testconfig.NatsTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipService;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.nats.client.Nats;
import jakarta.servlet.Filter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
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

    /**
     * The webhook role creates its JetStream streams on the shared NATS container at startup. Left there, they
     * make a later test that creates a stream of the same name with its own configuration fail.
     */
    @AfterEach
    void deleteTheStreamsTheRoleCreated() throws Exception {
        var connection = Nats.connect(NatsTestContainer.getServerUrl());
        try {
            var streams = connection.jetStreamManagement();
            for (String name : streams.getStreamNames()) {
                streams.deleteStream(name);
            }
        } finally {
            connection.close();
        }
    }

    @ParameterizedTest
    @EnumSource(RuntimeRole.class)
    void shouldResolveEveryApplicationBeanWhenOnlyOneRuntimeRoleIsEnabled(RuntimeRole role) {
        var database = PostgreSQLTestContainer.createMigratedDatabase(
                "runtime_role_" + role.name().toLowerCase(Locale.ROOT));
        var properties = Arrays.stream(RuntimeRole.values())
                .map(candidate -> candidate.property() + "=" + (candidate == role))
                .toArray(String[]::new);
        var configuration =
                BootstrapUtils.resolveTestContextBootstrapper(getClass()).buildMergedContextConfiguration();
        new WebApplicationContextRunner()
                .withInitializer(application -> {
                    var conversion = new ApplicationConversionService();
                    application.getEnvironment().setConversionService(conversion);
                    application.getBeanFactory().setConversionService(conversion);
                })
                .withInitializer(new ConfigDataApplicationContextInitializer())
                // SpringApplication applies this from spring.factories; a webhook-only context scans only the receiver.
                .withInitializer(new WebhookReceiverScope.Initializer())
                .withInitializer(application -> configuration
                        .getContextCustomizers()
                        .forEach(customizer -> customizer.customizeContext(application, configuration)))
                .withUserConfiguration(Application.class)
                .withPropertyValues(
                        "spring.profiles.active="
                                + (role == RuntimeRole.SERVER
                                        ? "default"
                                        : role.name().toLowerCase(Locale.ROOT)),
                        "spring.config.import=classpath:application-test.yml",
                        "spring.main.allow-bean-definition-overriding=false",
                        "spring.main.lazy-initialization=false",
                        "spring.sql.init.mode=never",
                        "spring.datasource.url=" + database.jdbcUrl(),
                        "spring.datasource.username=" + database.username(),
                        "spring.datasource.password=" + database.password(),
                        "hephaestus.agent.enabled=true",
                        "hephaestus.worker.capacity.review-max=1",
                        "hephaestus.worker.capacity.mentor-max=1",
                        "hephaestus.sync.nats.enabled=" + (role != RuntimeRole.WORKER),
                        "hephaestus.sync.nats.server=" + NatsTestContainer.getServerUrl(),
                        "hephaestus.sync.nats.username=",
                        "hephaestus.sync.nats.password=",
                        "hephaestus.integration.slack.enabled=true",
                        "hephaestus.integration.outline.enabled=true")
                .withPropertyValues(properties)
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(RuntimeRole.enabled(application.getEnvironment()))
                            .containsExactly(role);
                    assertThat(application.getBeansOfType(ScmRepositoryItemLifecycle.class))
                            .hasSize(role == RuntimeRole.SERVER ? 1 : 0);
                    if (role == RuntimeRole.SERVER) {
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
                    } else {
                        assertThat(application.getBeansWithAnnotation(WorkspaceScopedController.class))
                                .isEmpty();
                        assertThat(application).doesNotHaveBean(AccountReferenceService.class);
                        assertThat(application).doesNotHaveBean(ResearchParticipationQuery.class);
                        assertThat(application).doesNotHaveBean(MonitoredRepositoryQuery.class);
                        assertThat(application).doesNotHaveBean(PracticeReviewCoverageService.class);
                        assertThat(application).doesNotHaveBean(GitHubLifecycleListener.class);
                        assertThat(application).doesNotHaveBean(GitHubInstallationReconciler.class);
                        assertThat(application).doesNotHaveBean(GitHubWorkspaceProvisioningAdapter.class);
                        assertThat(application).doesNotHaveBean(SlackRetentionPurger.class);
                        assertThat(application).doesNotHaveBean(SlackWorkspaceContentEraser.class);
                        assertThat(application).doesNotHaveBean(SlackWorkspacePurgeAdapter.class);
                    }
                    if (role == RuntimeRole.WORKER) {
                        assertThat(application).hasSingleBean(ReviewJobExecutor.class);
                        assertThat(application).hasSingleBean(WorkerDrainCoordinator.class);
                        assertThat(application).hasSingleBean(ResultAdmissionPolicy.class);
                    } else {
                        assertThat(application).doesNotHaveBean(ReviewJobExecutor.class);
                    }
                    if (role == RuntimeRole.WEBHOOK) {
                        assertThat(application).hasSingleBean(JetStreamPublisher.class);
                        assertThat(application).doesNotHaveBean(ResultAdmissionPolicy.class);
                        assertThat(application).doesNotHaveBean(ResultMarkService.class);
                        assertThat(application).doesNotHaveBean(CitedSourceAccess.class);
                        assertThat(application).doesNotHaveBean(EvidenceDeliveryAuthorization.class);
                    } else {
                        assertThat(application).doesNotHaveBean(JetStreamPublisher.class);
                        assertThat(application).hasSingleBean(ResultMarkService.class);
                        assertThat(application).hasSingleBean(CitedSourceAccess.class);
                        assertThat(application).hasSingleBean(EvidenceDeliveryAuthorization.class);
                    }
                    assertSlackIngressOwnership(application, role);
                });
    }

    /**
     * Slack Events stay with the webhook role, which publishes them. Interactivity belongs to the server role, which
     * commits a button's consent change before it answers Slack. A worker verifies neither and needs no signing secret.
     */
    private static void assertSlackIngressOwnership(AssertableWebApplicationContext application, RuntimeRole role)
            throws Exception {
        switch (role) {
            case SERVER -> {
                assertThat(application).hasSingleBean(SlackSignatureVerifier.class);
                assertThat(application).hasSingleBean(SlackInteractivityController.class);
                assertThat(application).hasSingleBean(SlackInteractivityHandler.class);
                assertThat(application).doesNotHaveBean(SlackWebhookSignatureVerifier.class);
                assertSignedInteractivityIsAnswered(application);
            }
            case WEBHOOK -> {
                assertThat(application).hasSingleBean(SlackSignatureVerifier.class);
                assertThat(application).hasSingleBean(SlackWebhookSignatureVerifier.class);
                assertThat(application).doesNotHaveBean(SlackInteractivityController.class);
                assertThat(application).doesNotHaveBean(SlackInteractivityHandler.class);
            }
            case WORKER -> {
                assertThat(application).doesNotHaveBean(SlackSignatureVerifier.class);
                assertThat(application).doesNotHaveBean(SlackWebhookSignatureVerifier.class);
                assertThat(application).doesNotHaveBean(SlackInteractivityController.class);
            }
        }
    }

    /**
     * A button press through what the server-only context registers for the path, in the servlet order: the payload
     * bound, the raw-body cache, the security chain, then the controller's signature check and the real handler. The
     * press names a team no workspace is connected to, so the handler looks it up in the migrated database, finds
     * nothing to change and the request is answered 200.
     */
    private static void assertSignedInteractivityIsAnswered(AssertableWebApplicationContext application)
            throws Exception {
        List<Filter> guards = Arrays.stream(application.getBeanNamesForType(FilterRegistrationBean.class))
                .<FilterRegistrationBean<?>>map(name -> (FilterRegistrationBean<?>) application.getBean(name))
                .filter(registration -> registration.getUrlPatterns().contains(SLACK_INTERACTIVITY))
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
