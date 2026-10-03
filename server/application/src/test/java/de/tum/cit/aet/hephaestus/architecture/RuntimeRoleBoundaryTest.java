package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWebhookRole;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;

/**
 * Pins the {@code hephaestus.runtime.*} gates: every role defaults to on, a bean gates through the
 * composed role annotations, and the beans each role must or must not load carry the right gate.
 * See ADR 0005 and ADR 0008.
 */
class RuntimeRoleBoundaryTest extends HephaestusArchitectureTest {

    /**
     * Single property-gated config per role. Controllers inside {@code integration.webhook} are
     * implicitly gated via {@code @ConditionalOnBean(JetStreamPublisher.class)} — they auto-load
     * iff {@link de.tum.cit.aet.hephaestus.integration.core.webhook.WebhookConfiguration} loads, so
     * listing them here would just duplicate the WebhookConfiguration gate.
     */
    private static final Map<String, String> EXPECTED_GATES = Map.ofEntries(
            Map.entry(
                    "de.tum.cit.aet.hephaestus.integration.core.webhook.WebhookConfiguration",
                    RuntimeRole.WEBHOOK_PROPERTY),
            Map.entry("de.tum.cit.aet.hephaestus.core.runtime.ServerSchedulingConfig", RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.integration.core.consumer.IntegrationNatsConsumer",
                    RuntimeRole.SERVER_PROPERTY),
            Map.entry("de.tum.cit.aet.hephaestus.workspace.WorkspaceStartupListener", RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerConfiguration", RuntimeRole.WORKER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.agent.sandbox.docker.DockerSandboxConfiguration",
                    RuntimeRole.WORKER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.agent.sandbox.docker.AgentImagePullBootstrapper",
                    RuntimeRole.WORKER_PROPERTY),
            Map.entry("de.tum.cit.aet.hephaestus.core.runtime.hub.HubConfiguration", RuntimeRole.SERVER_PROPERTY),
            // Admin surfaces and the services behind them are server-only: the worker and webhook pods do
            // not load core.auth, so an admin controller mapped there is a route with no authentication
            // layer under it.
            Map.entry(
                    "de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionAdminController",
                    RuntimeRole.SERVER_PROPERTY),
            Map.entry("de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionService", RuntimeRole.SERVER_PROPERTY),
            Map.entry("de.tum.cit.aet.hephaestus.agent.catalog.LlmModelAdminController", RuntimeRole.SERVER_PROPERTY),
            Map.entry("de.tum.cit.aet.hephaestus.agent.catalog.LlmModelService", RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.agent.catalog.InstanceLlmSettingsController",
                    RuntimeRole.SERVER_PROPERTY),
            // NOT listed: InstanceLlmSettingsService. It is deliberately ungated — the workspace-scoped
            // read of the instance policy goes through it too, and the gate belongs on the admin surface.
            Map.entry("de.tum.cit.aet.hephaestus.agent.usage.LlmUsageAdminController", RuntimeRole.SERVER_PROPERTY),
            // Server-only so the worker and webhook pods never acquire an outbound dependency on
            // ecb.europa.eu — this fetcher is the only egress the display-currency feature has.
            Map.entry("de.tum.cit.aet.hephaestus.agent.usage.fx.FxRateFetchScheduler", RuntimeRole.SERVER_PROPERTY),
            // Email leaves the instance from the server role only: the gateway, the listeners that feed it,
            // the redelivery sweep and the admin verification surface all boot with it.
            Map.entry("de.tum.cit.aet.hephaestus.notification.email.EmailGateway", RuntimeRole.SERVER_PROPERTY),
            Map.entry("de.tum.cit.aet.hephaestus.notification.email.EmailAdminController", RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.notification.AccountDeletionEmailListener", RuntimeRole.SERVER_PROPERTY),
            Map.entry("de.tum.cit.aet.hephaestus.notification.NotificationRedeliveryJob", RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.notification.NotificationPublicationConfiguration",
                    RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.notification.AccountSecurityEmailListener", RuntimeRole.SERVER_PROPERTY),
            Map.entry("de.tum.cit.aet.hephaestus.notification.SurveyEmailDeliveryAdapter", RuntimeRole.SERVER_PROPERTY),
            Map.entry("de.tum.cit.aet.hephaestus.notification.SurveyEmailListener", RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.notification.email.EmailUnsubscribeController",
                    RuntimeRole.SERVER_PROPERTY),
            Map.entry("de.tum.cit.aet.hephaestus.notification.email.EmailRateLimiter", RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionService",
                    RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.notification.preferences.NotificationPreferencesController",
                    RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.notification.ProductFeedbackEmailPreparation",
                    RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.notification.ProductFeedbackEmailListener", RuntimeRole.SERVER_PROPERTY),
            Map.entry("de.tum.cit.aet.hephaestus.notification.SurveyEndedSummaryListener", RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.notification.WorkspaceAlertEmailListener", RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.notification.WorkspaceAlertEmailPreparation",
                    RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.notification.preferences.NotificationPreferencesControllerAdvice",
                    RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.notification.preferences.NotificationPreferencesExportAdapter",
                    RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.notification.preferences.NotificationAccountErasureAdapter",
                    RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.productfeedback.ProductFeedbackNotificationQueryService",
                    RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.productfeedback.SurveyEmailInvitationService",
                    RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.productfeedback.SurveyEmailSchedulingJob", RuntimeRole.SERVER_PROPERTY),

            // ServerSchedulingConfig silences the @Scheduled tick off-server, but an ungated BEAN still
            // registers its gauges — permanent zeros in agent.queue.* / mentor.in_flight.* from pods that
            // never sample. Gate the bean, not just the tick.
            Map.entry("de.tum.cit.aet.hephaestus.agent.job.AgentQueueHealthSampler", RuntimeRole.SERVER_PROPERTY),
            Map.entry("de.tum.cit.aet.hephaestus.agent.job.AgentJobRetentionService", RuntimeRole.SERVER_PROPERTY),
            // Product feedback is a member- and admin-facing web surface, and SurveyService reads research
            // consent through a port only the server role implements.
            Map.entry("de.tum.cit.aet.hephaestus.productfeedback.FeedbackController", RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.productfeedback.InstanceFeedbackController",
                    RuntimeRole.SERVER_PROPERTY),
            Map.entry("de.tum.cit.aet.hephaestus.productfeedback.FeedbackAdminController", RuntimeRole.SERVER_PROPERTY),
            Map.entry("de.tum.cit.aet.hephaestus.productfeedback.SurveyAdminController", RuntimeRole.SERVER_PROPERTY),
            Map.entry("de.tum.cit.aet.hephaestus.productfeedback.FeedbackService", RuntimeRole.SERVER_PROPERTY),
            Map.entry("de.tum.cit.aet.hephaestus.productfeedback.SurveyService", RuntimeRole.SERVER_PROPERTY),
            Map.entry("de.tum.cit.aet.hephaestus.agent.mentor.chat.MentorInFlightReaper", RuntimeRole.SERVER_PROPERTY),
            // ADR 0006: the LLM proxy runs beside the sandbox on the WORKER, and only there.
            Map.entry("de.tum.cit.aet.hephaestus.agent.proxy.LlmProxyController", RuntimeRole.WORKER_PROPERTY),
            Map.entry("de.tum.cit.aet.hephaestus.agent.proxy.LlmProxySecurityConfig", RuntimeRole.WORKER_PROPERTY),
            Map.entry("de.tum.cit.aet.hephaestus.agent.proxy.ProxyAccounting", RuntimeRole.WORKER_PROPERTY),
            Map.entry("de.tum.cit.aet.hephaestus.agent.proxy.ProxyUsageAccumulator", RuntimeRole.WORKER_PROPERTY),
            // The config-audit viewer and retention sweep are server-only; the recorder deliberately is
            // not, because every role writes to the trail.
            Map.entry("de.tum.cit.aet.hephaestus.core.audit.ConfigAuditRetentionJob", RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.core.audit.web.AdminConfigAuditController", RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.integration.slack.sync.status.SlackIntegrationSyncRunner",
                    RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerTokenExchangeController",
                    RuntimeRole.SERVER_PROPERTY),
            // WorkspaceContextFilter lives outside core.auth, so allAuthStereotypeBeansAreServerGated()
            // doesn't cover it — pin it here. (The core.auth beans are covered by that structural test.)
            Map.entry(
                    "de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextFilter", RuntimeRole.SERVER_PROPERTY),
            // Connection-management OAuth surface — server-only (the worker/webhook never run the OAuth
            // connect dance). Gating these is what unblocks those pods past HmacOAuthStateService.
            Map.entry(
                    "de.tum.cit.aet.hephaestus.integration.core.oauth.state.HmacOAuthStateService",
                    RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.integration.core.oauth.state.OAuthStateNonceStore",
                    RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.integration.core.oauth.state.OAuthStateNonceCleanupJob",
                    RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.integration.core.oauth.OAuthCallbackController",
                    RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.integration.core.connection.api.ConnectionController",
                    RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.integration.core.connection.api.ConnectionAdminService",
                    RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.integration.scm.github.connect.GitHubConnectionStrategy",
                    RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.integration.scm.gitlab.connect.GitLabConnectionStrategy",
                    RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.integration.slack.connect.SlackConnectionStrategy",
                    RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.integration.slack.connect.SlackOAuthClient",
                    RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.integration.slack.channel.SlackChannelAdminController",
                    RuntimeRole.SERVER_PROPERTY),
            // Outline admin/connect surface — server-only, mirroring the Slack entries above.
            Map.entry(
                    "de.tum.cit.aet.hephaestus.integration.outline.connect.OutlineConnectionStrategy",
                    RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.integration.outline.connect.OutlineConnectionAdminController",
                    RuntimeRole.SERVER_PROPERTY),
            Map.entry(
                    "de.tum.cit.aet.hephaestus.integration.outline.collection.OutlineCollectionAdminController",
                    RuntimeRole.SERVER_PROPERTY));

    /**
     * Beans that must wire <em>unconditionally</em> (no {@code @ConditionalOnProperty}): Heph is
     * offered to every member of an active workspace, and whether it can answer is the workspace's
     * mentor model binding in the DB, not a capability flag.
     */
    private static final List<String> UNCONDITIONAL_MENTOR_BEANS = List.of(
            "de.tum.cit.aet.hephaestus.agent.mentor.chat.MentorChatService",
            "de.tum.cit.aet.hephaestus.agent.mentor.chat.MentorChatController",
            "de.tum.cit.aet.hephaestus.agent.mentor.chat.MentorChatExecutorConfig",
            "de.tum.cit.aet.hephaestus.agent.mentor.chat.MentorChatMetrics");

    @Test
    void runtimeGatesAreMatchIfMissingTrue() {
        List<String> violations = classes.stream()
                .flatMap(clazz -> conditionalOnPropertyAnnotations(clazz).map(ann -> new ConditionalRef(clazz, ann)))
                .filter(ConditionalRef::targetsRuntimeRoleProperty)
                .filter(ConditionalRef::missingMatchIfMissingTrue)
                .map(ConditionalRef::describe)
                .collect(Collectors.toList());

        assertThat(violations)
                .as("Classes with hephaestus.runtime.* gates that don't set matchIfMissing=true")
                .isEmpty();
    }

    /**
     * A role gate is written once, as {@code @ConditionalOn{Server,Worker,Webhook}Role}, so its
     * {@code matchIfMissing} default cannot be dropped on one bean. A further condition sits beside the
     * composed annotation, since Boot requires every property condition on an element to match. The
     * exceptions are the composed annotations themselves and the gates no composed annotation expresses:
     * the sandbox gateway's rate-limit buckets exist only where the server role is off, and worker tokens
     * wherever the server or the worker role is on. Each exception must still carry an inline gate, so
     * one that no longer needs exempting fails here.
     */
    @Test
    void runtimeRoleGatesUseTheComposedAnnotations() {
        Set<String> exceptions = Set.of(
                ConditionalOnServerRole.class.getName(),
                ConditionalOnWorkerRole.class.getName(),
                ConditionalOnWebhookRole.class.getName(),
                "de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayConfiguration.sandboxGatewayBucketResolver()",
                "de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtConfiguration");
        List<String> gated = classes.stream()
                .filter(c -> c.getFullName().startsWith("de.tum.cit.aet.hephaestus."))
                .flatMap(c -> Stream.concat(
                        Stream.of(c)
                                .filter(RuntimeRoleBoundaryTest::hasInlineRoleGate)
                                .map(JavaClass::getFullName),
                        c.getMethods().stream()
                                .filter(RuntimeRoleBoundaryTest::hasInlineRoleGate)
                                .map(JavaMethod::getFullName)))
                .toList();

        assertThat(gated)
                .as("Every exemption still carries an inline role gate; remove one that no longer does")
                .containsAll(exceptions);
        assertThat(gated.stream().filter(element -> !exceptions.contains(element)))
                .as("Gate on @ConditionalOnServerRole, @ConditionalOnWorkerRole or @ConditionalOnWebhookRole, not on "
                        + "a property condition or expression naming a RuntimeRole property")
                .isEmpty();
    }

    @Test
    void enableSchedulingLivesOnlyOnServerSchedulingConfig() {
        List<String> hosts = classes.stream()
                .filter(c -> c.getFullName().startsWith("de.tum.cit.aet.hephaestus."))
                .filter(c -> c.isAnnotatedWith(EnableScheduling.class) || c.isMetaAnnotatedWith(EnableScheduling.class))
                .map(JavaClass::getFullName)
                .collect(Collectors.toList());

        assertThat(hosts)
                .as(
                        "@EnableScheduling (direct or meta-annotated) must appear on exactly one class (ServerSchedulingConfig); "
                                + "any other @Scheduled host relies on its absence on the webhook role to no-op silently — keep that invariant load-bearing.")
                .containsExactly("de.tum.cit.aet.hephaestus.core.runtime.ServerSchedulingConfig");
    }

    @Test
    void webhookPackageIsIsolatedFromServerWorkerConcerns() {
        noClasses()
                .that()
                .resideInAPackage("de.tum.cit.aet.hephaestus.integration.core.webhook..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "de.tum.cit.aet.hephaestus.workspace..",
                        "de.tum.cit.aet.hephaestus.activity..",
                        "de.tum.cit.aet.hephaestus.agent..")
                .because(
                        "webhook receiver is a pure publish-only role; depending on workspace/activity/agent would re-introduce "
                                + "the wiring leaks runtime testing already exposed (ObjectProvider cascade, etc.) and break role isolation")
                .check(classes);
    }

    /**
     * Every ungated bean that injects a server-gated bean crash-loops the worker and webhook pods at
     * context refresh — a failure no test tier can see, because none boots with
     * {@code hephaestus.runtime.server.enabled=false}. {@link java.time.Clock} is injected by beans that
     * must load on every role — the config-audit recorder among them — so gating its provider breaks both
     * pods with the whole suite green.
     */
    @Test
    void clockBeanIsAvailableToEveryRuntimeRole() {
        Set<JavaClass> providers = classes.stream()
                .filter(c -> c.isAnnotatedWith(Configuration.class))
                .filter(c -> c.getMethods().stream()
                        .anyMatch(m -> m.isAnnotatedWith(Bean.class)
                                && m.getRawReturnType().isAssignableTo(Clock.class)))
                .collect(Collectors.toSet());

        assertThat(providers)
                .as("exactly one @Bean Clock provider; two would make injection ambiguous")
                .hasSize(1);
        JavaClass provider = providers.iterator().next();
        assertThat(conditionalOnPropertyAnnotations(provider).findAny())
                .as(
                        "%s provides the Clock bean and must NOT be runtime-role gated — ungated beans on every "
                                + "role inject it, and gating it fails context refresh on worker/webhook",
                        provider.getName())
                .isEmpty();
    }

    @Test
    void expectedRuntimeGatesArePresent() {
        EXPECTED_GATES.forEach((fqn, expectedProperty) -> {
            JavaClass clazz = classes.stream()
                    .filter(c -> c.getFullName().equals(fqn))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Expected class not found in ArchUnit scan: " + fqn));
            boolean matched = conditionalOnPropertyAnnotations(clazz)
                    .map(ann -> new ConditionalRef(clazz, ann))
                    .anyMatch(ref -> ref.propertyNames().anyMatch(name -> name.equals(expectedProperty)));
            assertThat(matched)
                    .as("%s must be gated by @ConditionalOnProperty('%s')", fqn, expectedProperty)
                    .isTrue();
        });
    }

    /**
     * The {@code core.auth.spi} read-only query impls are the cross-role data-access part of auth
     * (account identity/role/name lookups), consumed by the connection-identity service, the
     * workspace and practices modules, and product feedback's name resolution and erasure adapters
     * on every role. They carry no hard prod env and must stay ungated — unlike the web/OAuth/issuance
     * layer.
     */
    private static final List<String> CROSS_ROLE_AUTH_SPI_IMPLS = List.of(
            "de.tum.cit.aet.hephaestus.core.auth.AccountIdentityQueryService",
            "de.tum.cit.aet.hephaestus.core.auth.AccountRoleQueryService",
            "de.tum.cit.aet.hephaestus.core.auth.AccountSummaryQueryService");

    @Test
    void allAuthStereotypeBeansAreServerGated() {
        // The whole core.auth module is the user-facing web/auth surface — server-role only, EXCEPT the
        // cross-role SPI query impls (see CROSS_ROLE_AUTH_SPI_IMPLS). This is the real drift guard: any new
        // auth stereotype bean added without @ConditionalOnServerRole would re-break the worker/webhook
        // pods (server.enabled=false). Repositories (interfaces, JPA-only) and @ConfigurationProperties
        // records are not stereotypes.
        List<String> ungated = classes.stream()
                .filter(c -> c.getPackageName().startsWith("de.tum.cit.aet.hephaestus.core.auth"))
                .filter(c -> !c.isInterface())
                .filter(c -> !CROSS_ROLE_AUTH_SPI_IMPLS.contains(c.getFullName()))
                .filter(c -> c.isMetaAnnotatedWith(Component.class))
                .filter(clazz -> conditionalOnPropertyAnnotations(clazz)
                        .map(ann -> new ConditionalRef(clazz, ann))
                        .noneMatch(
                                ref -> ref.propertyNames().anyMatch(name -> name.equals(RuntimeRole.SERVER_PROPERTY))))
                .map(JavaClass::getFullName)
                .collect(Collectors.toList());

        assertThat(ungated)
                .as(
                        "Every core.auth stereotype bean must carry @ConditionalOnServerRole — the auth web/auth surface is server-role only")
                .isEmpty();
    }

    @Test
    void mentorBeansWireUnconditionally() {
        List<String> stillGated = UNCONDITIONAL_MENTOR_BEANS.stream()
                .map(fqn -> classes.stream()
                        .filter(c -> c.getFullName().equals(fqn))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("Expected class not found in ArchUnit scan: " + fqn)))
                .filter(clazz ->
                        conditionalOnPropertyAnnotations(clazz).findAny().isPresent())
                .map(JavaClass::getFullName)
                .collect(Collectors.toList());

        assertThat(stillGated)
                .as("Mentor beans must wire unconditionally — Heph is always offered (per-workspace readiness "
                        + "is the mentor model binding), not behind a capability flag.")
                .isEmpty();
    }

    /*
     * There is deliberately no blanket "every @Scheduled class carries a runtime-role gate" rule.
     * @EnableScheduling lives on the SERVER-gated ServerSchedulingConfig, so off-server no @Scheduled
     * method fires, gated or not; the gate protects bean-construction side effects (meter
     * registration), not the tick. Every formulation of the rule needs an exemption list as long as
     * EXPECTED_GATES: @Bean-built sandbox classes that Spring never evaluates @ConditionalOnProperty on,
     * @Service classes whose schedule is secondary to their API (gating those deletes the service from
     * pods that call its other methods), and the rate-limit trackers, which must observe limits on
     * whichever role makes the API call.
     */

    @Test
    void noBeanIsGatedOnTheRetiredSandboxEnabledFlag() {
        // The Docker sandbox is the worker role and Heph is always offered, so no capability flag may
        // gate either, through @ConditionalOnProperty or @ConditionalOnExpression.
        List<String> violations = classes.stream()
                .filter(c -> c.getFullName().startsWith("de.tum.cit.aet.hephaestus."))
                .filter(clazz -> referencesRetiredSandboxFlag(clazz))
                .map(JavaClass::getFullName)
                .collect(Collectors.toList());

        assertThat(violations)
                .as("No bean may be gated on the retired hephaestus.sandbox.enabled flag")
                .isEmpty();
    }

    private static boolean referencesRetiredSandboxFlag(JavaClass clazz) {
        boolean viaProperty = conditionalOnPropertyAnnotations(clazz)
                .map(ann -> new ConditionalRef(clazz, ann))
                .anyMatch(ref -> ref.propertyNames().anyMatch(name -> name.equals("hephaestus.sandbox.enabled")));
        if (viaProperty) {
            return true;
        }
        return expressions(clazz).anyMatch(expr -> expr.contains("hephaestus.sandbox.enabled"));
    }

    private static boolean hasInlineRoleGate(HasAnnotations<?> element) {
        return expressions(element).anyMatch(expr -> expr.contains(RuntimeRole.PROPERTY_PREFIX))
                || element.getAnnotations().stream()
                        .flatMap(RuntimeRoleBoundaryTest::propertyConditions)
                        .anyMatch(ann ->
                                ConditionalRef.propertyNames(ann).anyMatch(RuntimeRoleBoundaryTest::isRoleProperty));
    }

    /** SpEL is opaque to the annotation model, so an expression is matched as text. */
    private static Stream<String> expressions(HasAnnotations<?> element) {
        return element.getAnnotations().stream()
                .filter(ann -> ann.getRawType().isEquivalentTo(ConditionalOnExpression.class))
                .map(ann -> String.valueOf(ann.getProperties().get("value")));
    }

    private static boolean isRoleProperty(String name) {
        return name.startsWith(RuntimeRole.PROPERTY_PREFIX);
    }

    /**
     * The property conditions on a class, including those a composed annotation such as
     * {@code @ConditionalOnServerRole} carries, which ArchUnit reports only as the composed annotation.
     */
    private static Stream<JavaAnnotation<?>> conditionalOnPropertyAnnotations(JavaClass clazz) {
        return clazz.getAnnotations().stream()
                .flatMap(ann -> Stream.concat(
                        propertyConditions(ann),
                        ann.getRawType().getAnnotations().stream()
                                .flatMap(RuntimeRoleBoundaryTest::propertyConditions)));
    }

    /** A property condition itself, or the entries of the container a repeated one compiles into. */
    private static Stream<JavaAnnotation<?>> propertyConditions(JavaAnnotation<?> ann) {
        if (ann.getRawType().isEquivalentTo(ConditionalOnProperty.class)
                || ann.getRawType().isEquivalentTo(ConditionalOnBooleanProperty.class)) {
            return Stream.of(ann);
        }
        if ((ann.getRawType().isEquivalentTo(ConditionalOnProperties.class)
                        || ann.getRawType().isEquivalentTo(ConditionalOnBooleanProperties.class))
                && ann.getProperties().get("value") instanceof Object[] entries) {
            return Stream.of(entries).filter(JavaAnnotation.class::isInstance).map(entry -> (JavaAnnotation<?>) entry);
        }
        return Stream.empty();
    }

    private record ConditionalRef(JavaClass owner, JavaAnnotation<?> annotation) {
        boolean targetsRuntimeRoleProperty() {
            return propertyNames().anyMatch(RuntimeRoleBoundaryTest::isRoleProperty);
        }

        boolean missingMatchIfMissingTrue() {
            return !Boolean.TRUE.equals(annotation.getProperties().get("matchIfMissing"));
        }

        String describe() {
            return owner.getFullName() + " — " + propertyNames().collect(Collectors.joining(", "));
        }

        Stream<String> propertyNames() {
            return propertyNames(annotation);
        }

        /** {@code name} and its alias {@code value}, each under {@code prefix} as Boot resolves them. */
        static Stream<String> propertyNames(JavaAnnotation<?> annotation) {
            Map<String, Object> properties = annotation.getProperties();
            String prefix = String.valueOf(properties.getOrDefault("prefix", ""));
            String qualifier = prefix.isEmpty() || prefix.endsWith(".") ? prefix : prefix + ".";
            return Stream.of("name", "value")
                    .map(properties::get)
                    .filter(Object[].class::isInstance)
                    .flatMap(names -> Stream.of((Object[]) names))
                    .map(name -> qualifier + name);
        }
    }
}
