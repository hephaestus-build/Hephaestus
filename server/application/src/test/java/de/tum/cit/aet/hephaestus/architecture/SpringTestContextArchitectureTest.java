package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.BeanOverride;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoBeans;

class SpringTestContextArchitectureTest extends HephaestusArchitectureTest {

    private static final Map<String, String> FULL_CONTEXT_ASSIGNMENTS = Map.ofEntries(
            assignment("workspace.RepositoryProviderScopeIntegrationTest", "repository-provider-scope"),
            assignment("workspace.ManualRepositoryAuthorityIntegrationTest", "manual-repository-authority"),
            assignment("workspace.WorkspaceRepositoryCoverageIntegrationTest", "repository-coverage"),
            assignment("StartupBudgetIntegrationTest", "startup"),
            assignment("agent.mentor.live.HephTurnLiveTest", "heph-live"),
            assignment("agent.mentor.weeklydigest.WeeklyDigestIntegrationTest", "review-evidence"),
            assignment("agent.practice.live.PracticeScriptLiveLlmTest", "practice-script-live"),
            assignment("core.auth.clientsession.ClientSessionConcurrencyIntegrationTest", "dev-login"),
            assignment("core.auth.clientsession.ClientSessionIntegrationTest", "dev-login"),
            assignment("core.auth.consent.ConsentSignInBootstrapIntegrationTest", "consent-sign-in"),
            assignment("core.auth.dev.DevLoginIntegrationTest", "dev-login"),
            assignment("core.auth.webauthn.PasskeyLifecycleIntegrationTest", "required-passkeys"),
            assignment("core.auth.webauthn.TestInstanceAssuranceIntegrationTest", "test-instance-assurance"),
            assignment("core.database.PracticeV1ReviewJobsIntegrationTest", "practice-upgrade-history"),
            assignment("integration.core.oauth.SlackOAuthCallbackConflictIntegrationTest", "slack-signed"),
            assignment("integration.core.sync.api.SyncControllerIntegrationTest", "sync-controller-focused"),
            assignment("integration.outline.OutlineFrameworkRegistrationIntegrationTest", "outline-enabled"),
            assignment(
                    "integration.outline.collection.OutlineCollectionAdminControllerIntegrationTest",
                    "outline-enabled"),
            assignment(
                    "integration.outline.connect.OutlineConnectionAdminControllerIntegrationTest", "outline-enabled"),
            assignment("integration.outline.item.DocumentItemSourceIntegrationTest", "outline-enabled"),
            assignment("integration.outline.sync.OutlineDocumentSyncIntegrationTest", "outline-enabled"),
            assignment("integration.scm.domain.item.ScmItemSourceIntegrationTest", "scm-source-currentness"),
            assignment("integration.scm.github.BaseGitHubLiveIntegrationTest", "github-live"),
            assignment(
                    "integration.scm.gitlab.credentials.GitLabTokenLifecycleIntegrationTest", "gitlab-token-lifecycle"),
            assignment(
                    "integration.scm.gitlab.workspace.GitLabWorkspaceEventRoutingIntegrationTest",
                    "gitlab-event-routing"),
            assignment(
                    "integration.scm.workspace.GitLabStoppedProjectServiceIntegrationTest", "gitlab-stopped-project"),
            assignment("integration.slack.SlackConsentLifecycleE2EIntegrationTest", "slack-lifecycle"),
            assignment("integration.slack.channel.SlackChannelAdminControllerIntegrationTest", "slack-signed"),
            assignment("integration.slack.item.SlackThreadProjectionIntegrationTest", "slack-ingest"),
            assignment("integration.slack.item.ThreadItemSourceIntegrationTest", "slack-enabled"),
            assignment("integration.slack.mentor.SlackMentorProjectorIntegrationTest", "slack-receipt-order"),
            assignment("notification.AccountDeletionEmailIntegrationTest", "email-capture"),
            assignment("notification.AccountSecurityEmailIntegrationTest", "email-capture"),
            assignment("notification.email.EmailAdminControllerIntegrationTest", "email-capture"),
            assignment("practices.acrossworkspace.PracticesAcrossWorkspaceIntegrationTest", "review-evidence"),
            assignment("practices.adapter.ConversationFeedbackErasureIntegrationTest", "review-fixture"),
            assignment("practices.adapter.PracticesWorkspacePurgeAdapterTest", "review-fixture"),
            assignment("practices.feedback.AbstractFeedbackDeliveryIntegrationTest", "review-feedbackdelivery"),
            assignment("agent.adapter.EvidenceDeliveryAuthorizationIntegrationTest", "review-delivery-authorization"),
            assignment("agent.context.providers.mentor.ReviewAttemptsContentSourceIntegrationTest", "review-fixture"),
            assignment(
                    "agent.context.providers.mentor.RetainedReviewContextRegressionIntegrationTest", "review-fixture"),
            assignment("practices.feedback.FeedbackGateIntegrationTest", "review-feedbackgate"),
            assignment("agent.handler.PublicReviewEligibilityIntegrationTest", "review-feedbackgate"),
            assignment("agent.proxy.ReviewCredentialScopeIntegrationTest", "review-credential-scope"),
            assignment("practices.feedback.FeedbackPreparationReceiptIntegrationTest", "review-preparation-receipt"),
            assignment("practices.feedback.FeedbackHistoryQueryIntegrationTest", "review-feedbackhistoryquery"),
            assignment("practices.feedback.FeedbackPrivateHistoryIntegrationTest", "review-privatehistory"),
            assignment("practices.feedback.FeedbackReviewControllerIntegrationTest", "review-feedbackreviewcontroller"),
            assignment("practices.groupdetail.PracticeGroupReviewHistoryIntegrationTest", "review-evidence"),
            assignment("practices.groupdetail.PracticeGroupReviewsControllerIntegrationTest", "review-fixture"),
            assignment("practices.profile.PracticeProfileOverviewIntegrationTest", "review-fixture"),
            assignment("practices.profile.PracticeProfileReviewIntegrationTest", "review-evidence"),
            assignment("practices.research.ResearchDisclosureControllerIntegrationTest", "research-disclosure-http"),
            assignment("practices.research.ResearchDisclosureIntegrationTest", "review-fixture"),
            assignment("practices.result.PracticeGroupStandingIntegrationTest", "review-fixture"),
            assignment("practices.review.AbstractResultAdmissionIntegrationTest", "review-resultadmission"),
            assignment("practices.result.ResultControllerIntegrationTest", "review-fixture"),
            assignment("practices.result.OwnWithdrawnFeedbackIntegrationTest", "review-evidence"),
            assignment("practices.result.MigratedHistoryReadIntegrationTest", "review-migrated-history"),
            assignment("practices.result.ResultHistoryIntegrationTest", "review-resulthistory"),
            assignment("practices.result.ResultMarkServiceIntegrationTest", "review-fixture"),
            assignment("practices.result.ResultRepositoryIntegrationTest", "review-fixture"),
            assignment("practices.review.DeliveredWorkFeedbackControllerIntegrationTest", "review-fixture"),
            assignment("practices.review.DeliveredWorkFeedbackRepositoryIntegrationTest", "review-fixture"),
            assignment("practices.review.PracticeReviewCoverageReplaceIntegrationTest", "review-fixture"),
            assignment("practices.review.PracticeReviewSettingsControllerIntegrationTest", "review-fixture"),
            assignment("practices.review.ProductItemQueryIntegrationTest", "review-fixture"),
            assignment("practices.review.DisabledIntegrationHistoryIntegrationTest", "review-disabled-integrations"),
            assignment("practices.review.ReviewContextControllerIntegrationTest", "review-fixture"),
            assignment("practices.review.ReviewGateIntegrationTest", "review-fixture"),
            assignment("practices.review.ReviewNarrativeHistoryIntegrationTest", "review-fixture"),
            assignment("practices.review.ReviewOperationalViewsIntegrationTest", "review-reviewoperationalviews"),
            assignment("practices.review.ReviewRequestControllerIntegrationTest", "review-fixture"),
            assignment("practices.review.ReviewSummaryControllerIntegrationTest", "review-fixture"),
            assignment("practices.review.ReviewTimingFactsIntegrationTest", "review-reviewtimingfacts"),
            assignment("practices.review.feedbackmode.FeedbackModeReviewIntegrationTest", "review-feedbackmodereview"),
            assignment("practices.review.schedule.ReviewBatchControllerIntegrationTest", "review-fixture"),
            assignment("practices.review.schedule.ReviewBatchScopeIntegrationTest", "review-fixture"),
            assignment("practices.review.schedule.ReviewScheduleControllerIntegrationTest", "review-fixture"),
            assignment("practices.reviewoutput.PracticeReviewItemFactsIntegrationTest", "review-fixture"),
            assignment("practices.reviewoutput.PracticeReviewOutputControllerIntegrationTest", "review-fixture"),
            assignment("practices.reviewoutput.ReviewLineageIntegrationTest", "review-fixture"),
            assignment("practices.scan.ScanReportIntegrationTest", "review-scanreport"),
            assignment("practices.trace.ItemTraceControllerIntegrationTest", "review-fixture"),
            assignment("productfeedback.ProductFeedbackEmailIntegrationTest", "email-capture"),
            assignment("productfeedback.SurveyEmailInvitationIntegrationTest", "email-capture"),
            assignment("testconfig.BaseIntegrationTest", "base"),
            assignment("testconfig.RealAuthIntegrationTest", "real-auth"));

    private static final Map<String, String> FULL_CONTEXT_JUSTIFICATIONS = Map.ofEntries(
            Map.entry("base", "shared PostgreSQL, HTTP, security, and application acceptance" + " context"),
            Map.entry(
                    "repository-provider-scope",
                    "real SQL provider, tenant and monitor lifetime enforcement with controlled GitLab transport and sync orchestration boundary"),
            Map.entry(
                    "manual-repository-authority",
                    "real encrypted credentials, connection lifecycle, tenant SQL and item directory with both provider transports controlled for denial and held-response proofs"),
            Map.entry(
                    "repository-coverage",
                    "real installation and token monitor coverage with only the token repository confirmation transport controlled"),
            Map.entry(
                    "consent-sign-in",
                    "real cookie and installed-client sign-in with the production consent" + " interceptor enabled"),
            Map.entry("dev-login", "dev-login feature-property behavior"),
            Map.entry(
                    "email-capture",
                    "shared configured sender identity and captured SMTP boundary for"
                            + " durable delivery and HTTP authorization"),
            Map.entry("github-live", "explicitly selected live GitHub profile and credentials"),
            Map.entry(
                    "gitlab-event-routing",
                    "real JetStream scope consumer with GitLab discovery, webhook and sync"
                            + " held at the API boundary"),
            Map.entry(
                    "gitlab-stopped-project",
                    "real stop receipts, kept items, encrypted credentials, connection locks and source deletion with only the GitLab transport that confirms a manual Add controlled"),
            Map.entry(
                    "gitlab-token-lifecycle",
                    "real credential storage, lifecycle locks and attention with only"
                            + " irreversible upstream token rotation mocked"),
            Map.entry("heph-live", "worker role with a real Heph host container, the agent image and a live model"),
            Map.entry("outline-enabled", "enabled Outline integration wiring"),
            Map.entry(
                    "practice-upgrade-history",
                    "authenticated HTTP reads and sibling custody/erasure against populated released history"
                            + " after the production migration"),
            Map.entry(
                    "practice-script-live",
                    "worker role with the real LLM proxy on a free sandbox gateway port," + " calling live models"),
            Map.entry("real-auth", "real OAuth and authentication wiring without test security"),
            Map.entry(
                    "test-instance-assurance",
                    "explicit operator-disabled assurance with real signed stale browser sessions, native role and CSRF enforcement, and saved workspace policies"),
            Map.entry(
                    "research-disclosure-http",
                    "real signed browser and installed-client tokens with configured research consent, unconditional fresh-passkey authorization, and persisted disclosure fixtures"),
            Map.entry(
                    "required-passkeys",
                    "mandatory instance-admin policy with real signed cookies, WebAuthn signatures, and database-backed recovery"),
            Map.entry(
                    "review-disabled-integrations",
                    "persisted mixed-provider history and real timing reads with Slack and Outline adapters disabled"),
            Map.entry(
                    "review-delivery-authorization",
                    "real stored result and problem proof with only current source access controlled"),
            Map.entry(
                    "review-evidence",
                    "shared review fixture with the neutral evidence authorization boundary"
                            + " controlled for exact visibility assertions"),
            Map.entry(
                    "review-feedbackgate",
                    "real feedback eligibility with only source capture and evidence"
                            + " authorization boundaries controlled"),
            Map.entry(
                    "review-feedbackdelivery",
                    "real feedback receipts and recovery with irreversible delivery and"
                            + " readiness boundaries controlled"),
            Map.entry(
                    "review-preparation-receipt",
                    "real preparation and page projection with developer identity, visibility and pattern eligibility controlled"),
            Map.entry(
                    "review-feedbackhistoryquery",
                    "real persisted history with exact private/public source permissions" + " controlled"),
            Map.entry(
                    "review-credential-scope",
                    "real job scopes, result admission and feedback preparation with only native SCM boundaries"
                            + " controlled"),
            Map.entry(
                    "review-privatehistory",
                    "real private history, routing and person-copy custody with only native SCM boundaries controlled"),
            Map.entry(
                    "review-feedbackmodereview",
                    "real feedback mode inheritance with runtime attempt authority" + " controlled"),
            Map.entry(
                    "review-feedbackreviewcontroller",
                    "real authenticated feedback routes with item display labels" + " controlled"),
            Map.entry(
                    "review-migrated-history",
                    "real persisted historical reads with only current SCM source access controlled"),
            Map.entry(
                    "review-fixture",
                    "shared persisted review/item/account fixture with real product"
                            + " services and schema functions"),
            Map.entry(
                    "review-resultadmission",
                    "real transactional result admission with controlled runtime attempts,"
                            + " budget and custody boundaries"),
            Map.entry(
                    "review-resulthistory",
                    "real persisted result history with runtime and source permission" + " boundaries controlled"),
            Map.entry(
                    "review-reviewoperationalviews",
                    "real operational HTTP product views with worker runtime facts" + " controlled"),
            Map.entry(
                    "review-reviewtimingfacts",
                    "real retained product timing and bounded percentile queries with"
                            + " provider merge facts controlled"),
            Map.entry(
                    "review-scanreport",
                    "real scan report membership, marks and visibility with runtime/person" + " boundaries controlled"),
            Map.entry(
                    "scm-source-currentness",
                    "real native SCM capture and current-row reconciliation with a listener spy"
                            + " for transactional rollback and durable retry"),
            Map.entry("slack-enabled", "real Slack item resolution with the configured provider adapter"),
            Map.entry("slack-ingest", "enabled Slack ingest wiring with its review-submission spy"),
            Map.entry("slack-lifecycle", "agent submission boundary override"),
            Map.entry(
                    "slack-receipt-order",
                    "real turn endings and persisted feedback receipts with the completion listener held"
                            + " to replay both publication orders"),
            Map.entry("slack-signed", "enabled signed Slack HTTP wiring"),
            Map.entry("startup", "production main-method startup instrumentation"),
            Map.entry("sync-controller-focused", "controlled sync provider and runner behavior"));

    private static final Set<String> SPRING_BOOT_TESTS = names(
            "StartupBudgetIntegrationTest", "testconfig.BaseIntegrationTest", "testconfig.RealAuthIntegrationTest");

    private static final Set<String> JSON_TESTS = names(
            "integration.MultiVersionFixtureTest",
            "integration.outline.client.OutlineApiFixtureDeserializationTest",
            "integration.outline.client.OutlineDeserializationToleranceTest");

    private static final Set<String> DATA_JPA_TESTS = names(
            "core.auth.clientsession.ClientSessionMigratedSchemaTest",
            "integration.schema.ProductionSchemaContractIntegrationTest");

    private static final Set<String> DYNAMIC_PROPERTY_TESTS = names(
            "StartupBudgetIntegrationTest",
            "agent.mentor.live.HephTurnLiveTest",
            "agent.practice.live.PracticeScriptLiveLlmTest",
            "core.auth.clientsession.ClientSessionMigratedSchemaTest",
            "integration.schema.ProductionSchemaContractIntegrationTest",
            "integration.scm.gitlab.workspace.GitLabWorkspaceEventRoutingIntegrationTest",
            "agent.proxy.ReviewCredentialScopeIntegrationTest",
            "practices.feedback.FeedbackPrivateHistoryIntegrationTest",
            "testconfig.BaseIntegrationTest",
            "testconfig.RealAuthIntegrationTest");

    private static final Set<String> PROPERTY_SOURCE_TESTS = names(
            "core.auth.webauthn.TestInstanceAssuranceIntegrationTest",
            "core.auth.webauthn.PasskeyLifecycleIntegrationTest",
            "core.database.PracticeV1ReviewJobsIntegrationTest",
            "core.auth.clientsession.ClientSessionConcurrencyIntegrationTest",
            "core.auth.clientsession.ClientSessionIntegrationTest",
            "core.auth.consent.ConsentSignInBootstrapIntegrationTest",
            "core.auth.dev.DevLoginIntegrationTest",
            "integration.core.oauth.SlackOAuthCallbackConflictIntegrationTest",
            "integration.outline.OutlineFrameworkRegistrationIntegrationTest",
            "integration.outline.collection.OutlineCollectionAdminControllerIntegrationTest",
            "integration.outline.connect.OutlineConnectionAdminControllerIntegrationTest",
            "integration.outline.item.DocumentItemSourceIntegrationTest",
            "integration.outline.sync.OutlineDocumentSyncIntegrationTest",
            "integration.slack.SlackConsentLifecycleE2EIntegrationTest",
            "integration.slack.channel.SlackChannelAdminControllerIntegrationTest",
            "integration.slack.item.SlackThreadProjectionIntegrationTest",
            "integration.slack.item.ThreadItemSourceIntegrationTest",
            "notification.AccountDeletionEmailIntegrationTest",
            "notification.AccountSecurityEmailIntegrationTest",
            "notification.email.EmailAdminControllerIntegrationTest",
            "practices.research.ResearchDisclosureControllerIntegrationTest",
            "practices.review.DisabledIntegrationHistoryIntegrationTest",
            "productfeedback.ProductFeedbackEmailIntegrationTest",
            "productfeedback.SurveyEmailInvitationIntegrationTest",
            "testconfig.BaseIntegrationTest",
            "testconfig.RealAuthIntegrationTest");

    private static final Set<String> DIRTY_CONTEXT_TESTS = Set.of();

    private static final Set<String> MOCKITO_BEAN_TESTS = names(
            "workspace.ManualRepositoryAuthorityIntegrationTest",
            "workspace.WorkspaceRepositoryCoverageIntegrationTest",
            "core.database.PracticeV1ReviewJobsIntegrationTest",
            "agent.adapter.EvidenceDeliveryAuthorizationIntegrationTest",
            "agent.mentor.weeklydigest.WeeklyDigestIntegrationTest",
            "agent.handler.PublicReviewEligibilityIntegrationTest",
            "agent.proxy.ReviewCredentialScopeIntegrationTest",
            "practices.result.MigratedHistoryReadIntegrationTest",
            "integration.scm.gitlab.credentials.GitLabTokenLifecycleIntegrationTest",
            "integration.scm.gitlab.workspace.GitLabWorkspaceEventRoutingIntegrationTest",
            "integration.scm.workspace.GitLabStoppedProjectServiceIntegrationTest",
            "integration.slack.SlackConsentLifecycleE2EIntegrationTest",
            "practices.acrossworkspace.PracticesAcrossWorkspaceIntegrationTest",
            "practices.feedback.AbstractFeedbackDeliveryIntegrationTest",
            "practices.feedback.FeedbackGateIntegrationTest",
            "practices.feedback.FeedbackPreparationReceiptIntegrationTest",
            "practices.feedback.FeedbackHistoryQueryIntegrationTest",
            "practices.feedback.FeedbackPrivateHistoryIntegrationTest",
            "practices.feedback.FeedbackReviewControllerIntegrationTest",
            "practices.groupdetail.PracticeGroupReviewHistoryIntegrationTest",
            "practices.profile.PracticeProfileReviewIntegrationTest",
            "practices.result.OwnWithdrawnFeedbackIntegrationTest",
            "practices.review.AbstractResultAdmissionIntegrationTest",
            "practices.result.ResultHistoryIntegrationTest",
            "practices.review.ReviewOperationalViewsIntegrationTest",
            "practices.review.ReviewTimingFactsIntegrationTest",
            "practices.review.feedbackmode.FeedbackModeReviewIntegrationTest",
            "practices.scan.ScanReportIntegrationTest",
            "workspace.RepositoryProviderScopeIntegrationTest");

    @Test
    void shouldMatchReviewedContextDeclarationsWhenArchitectureTestsRun() {
        assertInventory(SpringBootTest.class, SPRING_BOOT_TESTS);
        assertInventory(JsonTest.class, JSON_TESTS);
        assertInventory(DataJpaTest.class, DATA_JPA_TESTS);
        assertInventory(TestPropertySource.class, PROPERTY_SOURCE_TESTS);
        assertInventory(DirtiesContext.class, DIRTY_CONTEXT_TESTS);
        Set<String> mockitoBeans = classesWithTests.stream()
                .filter(javaClass -> javaClass.isAnnotatedWith(MockitoBean.class)
                        || javaClass.isAnnotatedWith(MockitoBeans.class)
                        || javaClass.getFields().stream()
                                .anyMatch(field -> field.isAnnotatedWith(MockitoBean.class)
                                        || field.isAnnotatedWith(MockitoBeans.class)))
                .map(JavaClass::getName)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(mockitoBeans)
                .as("@MockitoBean changes Spring's context-cache key; reuse an existing"
                        + " configuration or review this inventory")
                .isEqualTo(MOCKITO_BEAN_TESTS);

        Set<String> dynamicProperties = classesWithTests.stream()
                .flatMap(javaClass -> javaClass.getMethods().stream())
                .filter(method -> method.isAnnotatedWith(DynamicPropertySource.class))
                .map(method -> method.getOwner().getName())
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(dynamicProperties)
                .as("@DynamicPropertySource changes Spring's context-cache key; reuse a" + " reviewed test base")
                .isEqualTo(DYNAMIC_PROPERTY_TESTS);
    }

    @Test
    void shouldKeepTheReviewedFullApplicationContextBudgetWhenArchitectureTestsRun() {
        Set<JavaClass> fullContextTests = classesWithTests.stream()
                .filter(this::usesFullApplicationContext)
                .collect(Collectors.toSet());
        Set<String> configurationBearingTests = fullContextTests.stream()
                .filter(this::hasDirectContextCustomizer)
                .map(JavaClass::getName)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(configurationBearingTests)
                .as("Every full-context configuration variant must be assigned to a reviewed" + " merged key")
                .isEqualTo(FULL_CONTEXT_ASSIGNMENTS.keySet());

        Set<String> mergedKeys =
                fullContextTests.stream().map(this::mergedContextKey).collect(Collectors.toCollection(TreeSet::new));
        assertThat(FULL_CONTEXT_JUSTIFICATIONS.keySet()).isEqualTo(mergedKeys);
        assertThat(FULL_CONTEXT_JUSTIFICATIONS.values())
                .allSatisfy(reason -> assertThat(reason).isNotBlank());
    }

    @Test
    void shouldRejectLegacyMockBeansWhenArchitectureTestsRun() {
        Set<String> declarations = classesWithTests.stream()
                .filter(javaClass -> hasAnnotationNamed(javaClass.getAnnotations(), "MockBean", "MockBeans")
                        || javaClass.getFields().stream()
                                .anyMatch(field -> hasAnnotationNamed(field.getAnnotations(), "MockBean", "MockBeans")))
                .map(JavaClass::getName)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(declarations)
                .as("Use Spring Framework @MockitoBean; legacy @MockBean is forbidden")
                .isEmpty();
    }

    @Test
    void shouldDetectImplicitContextCustomizersWhenArchitectureTestsRun() {
        assertThat(hasDirectContextCustomizer(classesWithTests.get(NestedConfigurationFixture.class)))
                .isTrue();
        assertThat(hasDirectContextCustomizer(classesWithTests.get(TestBeanFixture.class)))
                .isTrue();
    }

    private boolean hasAnnotationNamed(Set<? extends JavaAnnotation<?>> annotations, String... names) {
        Set<String> expectedNames = Set.of(names);
        return annotations.stream()
                .anyMatch(annotation ->
                        expectedNames.contains(annotation.getRawType().getSimpleName()));
    }

    private void assertInventory(Class<? extends Annotation> annotation, Set<String> expected) {
        Set<String> actual = classesWithTests.stream()
                .filter(javaClass -> javaClass.isAnnotatedWith(annotation))
                .map(JavaClass::getName)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(actual)
                .as(
                        "@%s changes Spring's context-cache key; reuse an existing configuration or"
                                + " review this inventory",
                        annotation.getSimpleName())
                .isEqualTo(expected);
    }

    private boolean usesFullApplicationContext(JavaClass javaClass) {
        return (javaClass.isAnnotatedWith(SpringBootTest.class)
                || javaClass.getAllRawSuperclasses().stream()
                        .anyMatch(superclass -> superclass.getName().equals(name("testconfig.BaseIntegrationTest"))
                                || superclass.getName().equals(name("testconfig.RealAuthIntegrationTest"))));
    }

    private boolean hasDirectContextCustomizer(JavaClass javaClass) {
        Set<String> cacheKeyAnnotations = Set.of(
                "ActiveProfiles",
                "ContextConfiguration",
                "ContextHierarchy",
                "DirtiesContext",
                "Import",
                "MockitoBean",
                "MockitoBeans",
                "MockitoSpyBean",
                "MockitoSpyBeans",
                "SpringBootTest",
                "TestPropertySource",
                "WebAppConfiguration");
        return (javaClass.getAnnotations().stream()
                        .anyMatch(annotation -> cacheKeyAnnotations.contains(
                                annotation.getRawType().getSimpleName()))
                || javaClass.getFields().stream()
                        .anyMatch(field -> field.getAnnotations().stream().anyMatch(this::isBeanOverride))
                || javaClass.getMethods().stream()
                        .anyMatch(method -> method.isAnnotatedWith(DynamicPropertySource.class))
                || classesWithTests.stream()
                        .filter(candidate -> isNestedInside(candidate, javaClass))
                        .anyMatch(candidate -> candidate.isAnnotatedWith(TestConfiguration.class)));
    }

    private boolean isBeanOverride(JavaAnnotation<?> annotation) {
        return annotation.getRawType().isMetaAnnotatedWith(BeanOverride.class);
    }

    private boolean isNestedInside(JavaClass candidate, JavaClass owner) {
        JavaClass current = candidate;
        while (current.getEnclosingClass().isPresent()) {
            current = current.getEnclosingClass().orElseThrow();
            if (current.equals(owner)) return true;
        }
        return false;
    }

    private String mergedContextKey(JavaClass javaClass) {
        String assigned = FULL_CONTEXT_ASSIGNMENTS.get(javaClass.getName());
        if (assigned != null) return assigned;
        boolean realAuth = javaClass.getAllRawSuperclasses().stream()
                .anyMatch(superclass -> superclass.getName().equals(name("testconfig.RealAuthIntegrationTest")));
        return realAuth ? "real-auth" : "base";
    }

    private static Set<String> names(String... relativeNames) {
        return Arrays.stream(relativeNames)
                .map(name -> "de.tum.cit.aet.hephaestus." + name)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Map.Entry<String, String> assignment(String relativeName, String key) {
        return Map.entry(name(relativeName), key);
    }

    private static String name(String relativeName) {
        return "de.tum.cit.aet.hephaestus." + relativeName;
    }

    private static final class NestedConfigurationFixture {

        @SuppressWarnings("UnusedNestedClass") // ArchUnit reads it as the nested @TestConfiguration under
        // test
        @TestConfiguration
        static class Configuration {}
    }

    private static final class TestBeanFixture {

        @SuppressWarnings("UnusedVariable") // ArchUnit reads its @TestBean annotation; Spring never loads the
        // fixture
        @TestBean
        @Nullable
        String dependency;
    }
}
