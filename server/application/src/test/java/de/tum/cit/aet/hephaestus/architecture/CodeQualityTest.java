package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.*;
import static de.tum.cit.aet.hephaestus.architecture.conditions.HephaestusConditions.*;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

/**
 * Code Quality Tests - Detect anti-patterns and code smells.
 *
 * <p>These tests enforce quality standards:
 * <ul>
 *   <li>God class detection (SRP violations)</li>
 *   <li>Constructor parameter limits</li>
 *   <li>Interface Segregation Principle</li>
 *   <li>Dependency Inversion patterns</li>
 * </ul>
 *
 * <p>All thresholds are defined in {@link ArchitectureTestConstants}.
 *
 * @see ArchitectureTestConstants
 */
class CodeQualityTest extends HephaestusArchitectureTest {

    @Nested
    class GodClassTests {

        /**
         * Orchestrator services that coordinate many sub-services may legitimately exceed the
         * threshold; each exemption below carries its own justification.
         */
        @Test
        void servicesHaveLimitedConstructorParams() {
            Set<String> orchestratorExceptions = Set.of(
                    "GithubDataSyncService", // Coordinates 15 entity-specific sync services
                    "GitHubHistoricalBackfillService", // Coordinates multiple sync services for historical data
                    // backfill
                    "GitHubPullRequestSyncService", // Coordinates review, review comment, and project item sub-sync
                    // services
                    "WorkspaceProvisioningService", // Orchestrates provisioning across GitHub and GitLab providers
                    "MentorChatService", // Coordinates persistence, SSE, runner, lock, metrics, executor, llm config,
                    // context build
                    // Full provider-lifecycle orchestrators with broad ConnectionService usage —
                    // splitting them further scatters related logic without reducing coupling.
                    "GitLabWorkspaceInitializationService",
                    "WorkspaceRepositoryMonitorService");

            ArchRule rule = classes()
                    .that()
                    .haveSimpleNameEndingWith("Service")
                    .and()
                    .areAnnotatedWith(Service.class)
                    .should(haveAtMostConstructorParameters(MAX_SERVICE_DEPENDENCIES, orchestratorExceptions))
                    .because("God classes with many dependencies violate SRP and are hard to test");

            rule.check(classes);
        }

        @Test
        @DisplayName("Controllers have max 5 dependencies")
        void controllersAreThin() {
            ArchRule rule = classes()
                    .that()
                    .areAnnotatedWith(RestController.class)
                    .should(haveAtMostConstructorParameters(MAX_CONTROLLER_DEPENDENCIES))
                    .because("Controllers should delegate to services, not orchestrate many dependencies");

            rule.check(classes);
        }

        /** Business methods exclude getters, setters, equals, hashCode, toString, and constructors. */
        @Test
        void servicesHaveLimitedBusinessMethods() {
            ArchRule rule = classes()
                    .that()
                    .haveSimpleNameEndingWith("Service")
                    .and()
                    .areAnnotatedWith(Service.class)
                    .should(haveAtMostBusinessMethods(MAX_SERVICE_METHODS))
                    .because("Services with many methods violate SRP and should be split into focused services");

            rule.check(classes);
        }
    }

    @Nested
    class MethodComplexityTests {

        private static final int MAX_METHOD_PARAMETERS = 6;

        @Test
        void methodsHaveLimitedParameters() {
            Set<String> allowedMethods = Set.of(
                    // Activity writes carry the event identity, context and XP through the SPI.
                    "ActivityEventService.record",
                    "ActivityRecorder.record",
                    // @Bean factory wiring Spring dependencies — not business logic complexity
                    "DockerSandboxConfiguration.dockerSandboxAdapter",
                    "DockerSandboxConfiguration.dockerInteractiveSandboxAdapter");

            // Repository native SQL methods require individual @Param annotations and cannot use
            // parameter objects. These are atomic upsert operations that map directly to DB columns.
            Set<String> nativeSqlRepositoryMethods = Set.of(
                    "ActivityEventRepository.insertIfAbsent",
                    "IssueRepository.upsertCore",
                    "PullRequestRepository.upsertCore",
                    "MilestoneRepository.insertIfAbsent",
                    "LabelRepository.insertIfAbsent",
                    "ProjectRepository.upsertCore",
                    "ProjectFieldRepository.upsertCore",
                    "ProjectItemRepository.upsertCore",
                    "ProjectFieldValueRepository.upsertCore",
                    "ProjectStatusUpdateRepository.upsertCore",
                    "UserRepository.upsertUser",
                    "OrganizationRepository.upsert",
                    "CommitRepository.upsertCommit",
                    "CommitRepository.updateEnrichmentMetadata",
                    "DiscussionCategoryRepository.upsertCategory",
                    "DiscussionRepository.upsertCore",
                    "ObservationRepository.insertIfAbsent",
                    // Native INSERT ... ON CONFLICT DO NOTHING for an ingested Slack message; each column
                    // (workspace/team/channel/ts/thread_ts/author/member/text) is a distinct @Param.
                    "SlackMessageRepository.insertIfAbsent",
                    // Native durable-tombstone UPSERT (INSERT ... ON CONFLICT DO UPDATE); each column is a distinct
                    // @Param.
                    "SlackMessageRepository.tombstone",
                    // JPQL admin-audit query: each nullable filter needs its own @Param for the
                    // CAST(:from AS Instant) IS NULL null-handling — a param object can't express it.
                    "AuthEventRepository.findForAdmin",
                    // Atomic JPQL terminal transition; Spring Data query parameters must remain individually bound.
                    "SyncJobRepository.completeActiveJob",
                    // Developer observation feed: every optional facet needs its own @Param for the
                    // (:hasX = FALSE OR col IN (:xs)) empty-list guard — an IN () is invalid SQL, and Spring Data
                    // cannot bind a parameter object's components to named placeholders.
                    "ObservationRepository.findByAboutUserAndWorkspace",
                    "ObservationRepository.findByAboutUserAndWorkspaceSeverityFirst",
                    // Review-run pagination at the run grain: same per-facet @Param binding, plus the
                    // native run projection whose GROUP BY cannot be expressed through a derived query.
                    "ObservationRepository.findPracticeGroupReviewRuns",
                    "ObservationRepository.findPracticeGroupReviewRunObservations");

            ArchCondition<JavaClass> haveMethodsWithLimitedParams =
                    new ArchCondition<>("have methods with at most " + MAX_METHOD_PARAMETERS + " parameters") {
                        @Override
                        public void check(JavaClass javaClass, ConditionEvents events) {
                            javaClass.getMethods().stream()
                                    .filter(m -> m.getOwner().equals(javaClass))
                                    .filter(m -> !m.getName().startsWith("$"))
                                    .filter(m -> !m.getName().equals("<init>"))
                                    .filter(m -> !m.getName().startsWith("lambda$"))
                                    // @Recover methods must match the protected method's signature (Spring Retry)
                                    .filter(m -> !m.isAnnotatedWith("org.springframework.retry.annotation.Recover"))
                                    // Exclude static factory methods (common pattern for parameter objects)
                                    .filter(m -> !(m.getModifiers().contains(JavaModifier.STATIC)
                                            && (m.getName().equals("simple")
                                                    || m.getName().equals("of")
                                                    || m.getName().equals("from"))))
                                    .filter(m ->
                                            !allowedMethods.contains(javaClass.getSimpleName() + "." + m.getName()))
                                    // Native SQL methods require @Param per column and cannot use parameter objects
                                    .filter(m -> !nativeSqlRepositoryMethods.contains(
                                            javaClass.getSimpleName() + "." + m.getName()))
                                    .filter(m -> m.getModifiers().contains(JavaModifier.PUBLIC))
                                    .forEach(method -> {
                                        int paramCount =
                                                method.getRawParameterTypes().size();
                                        if (paramCount > MAX_METHOD_PARAMETERS) {
                                            events.add(SimpleConditionEvent.violated(
                                                    javaClass,
                                                    String.format(
                                                            "COMPLEXITY: %s.%s has %d parameters (max %d) - consider parameter object",
                                                            javaClass.getSimpleName(),
                                                            method.getName(),
                                                            paramCount,
                                                            MAX_METHOD_PARAMETERS)));
                                        }
                                    });
                        }
                    };

            ArchRule rule = classes()
                    .that()
                    .resideInAPackage(BASE_PACKAGE + "..")
                    .and()
                    .resideOutsideOfPackage(GENERATED_GRAPHQL_PACKAGE)
                    .and()
                    .areNotAnonymousClasses()
                    .and()
                    .areNotMemberClasses()
                    .should(haveMethodsWithLimitedParams)
                    .because("Too many parameters indicates complex methods needing refactoring");

            rule.check(classes);
        }

        /** Boolean-parameter count is a proxy here since ArchUnit cannot measure cyclomatic complexity directly. */
        @Test
        void serviceMethodsAvoidExcessiveBooleanParams() {
            ArchCondition<JavaClass> avoidManyBooleans =
                    new ArchCondition<>("avoid methods with more than 2 boolean parameters") {
                        @Override
                        public void check(JavaClass javaClass, ConditionEvents events) {
                            javaClass.getMethods().stream()
                                    .filter(m -> m.getOwner().equals(javaClass))
                                    .filter(m -> !m.getName().startsWith("$"))
                                    .forEach(method -> {
                                        long booleanCount = method.getRawParameterTypes().stream()
                                                .filter(p -> p.getName().equals("boolean")
                                                        || p.getName().equals("java.lang.Boolean"))
                                                .count();
                                        if (booleanCount > 2) {
                                            events.add(SimpleConditionEvent.violated(
                                                    javaClass,
                                                    String.format(
                                                            "COMPLEXITY: %s.%s has %d boolean params - consider using enum or builder",
                                                            javaClass.getSimpleName(),
                                                            method.getName(),
                                                            booleanCount)));
                                        }
                                    });
                        }
                    };

            ArchRule rule = classes()
                    .that()
                    .haveSimpleNameEndingWith("Service")
                    .should(avoidManyBooleans)
                    .because("Multiple boolean parameters indicate complexity and poor API design");

            rule.check(classes);
        }
    }

    @Nested
    @DisplayName("Security Patterns")
    class SecurityPatternTests {

        @Test
        void tokenServicesInSecurityPackages() {
            ArchCondition<JavaClass> beInTokenAppropriatePackage =
                    new ArchCondition<>("be in security, auth, app, common, or github package") {
                        @Override
                        public void check(JavaClass javaClass, ConditionEvents events) {
                            String packageName = javaClass.getPackageName();
                            boolean isInAppropriatePackage = packageName.contains(".app")
                                    || packageName.contains(".auth")
                                    || packageName.contains(".security")
                                    || packageName.contains(".common")
                                    || packageName.contains(".github");

                            if (!isInAppropriatePackage) {
                                events.add(SimpleConditionEvent.violated(
                                        javaClass,
                                        String.format(
                                                "%s handles tokens but is not in app/auth/security/common/github package",
                                                javaClass.getSimpleName())));
                            }
                        }
                    };

            ArchRule rule = classes()
                    .that()
                    .haveSimpleNameContaining("Token")
                    .and()
                    .haveSimpleNameEndingWith("Service")
                    .should(beInTokenAppropriatePackage)
                    .because("Token handling should be centralized in security-related packages");

            rule.check(classes);
        }
    }

    @Nested
    class InterfaceSegregationTests {

        @Test
        void interfacesHaveLimitedMethods() {
            ArchCondition<JavaClass> haveLimitedMethods =
                    new ArchCondition<>("have at most " + MAX_INTERFACE_METHODS + " methods") {
                        @Override
                        public void check(JavaClass javaClass, ConditionEvents events) {
                            // Skip Spring Data repositories - they have many default methods
                            if (javaClass.isAssignableTo(Repository.class)) {
                                return;
                            }

                            // Skip Spring Data query-projection interfaces (interfaces nested inside a repository):
                            // these are data carriers shaped by a query's SELECT list, not behavioural interfaces, so
                            // the
                            // ISP "small interface" rule does not apply (same rationale as the generated-model
                            // exclusion).
                            if (javaClass
                                    .getEnclosingClass()
                                    .map(enclosing -> enclosing.isAssignableTo(Repository.class))
                                    .orElse(false)) {
                                return;
                            }

                            int methodCount = (int) javaClass.getMethods().stream()
                                    .filter(m -> !m.getModifiers().contains(JavaModifier.STATIC))
                                    .filter(m -> m.getModifiers().contains(JavaModifier.ABSTRACT))
                                    .count();

                            if (methodCount > MAX_INTERFACE_METHODS) {
                                events.add(SimpleConditionEvent.violated(
                                        javaClass,
                                        String.format(
                                                "%s has %d abstract methods (max %d) - ISP violation",
                                                javaClass.getSimpleName(), methodCount, MAX_INTERFACE_METHODS)));
                            }
                        }
                    };

            ArchRule rule = classes()
                    .that()
                    .areInterfaces()
                    .and()
                    .resideInAPackage(BASE_PACKAGE + "..")
                    .and()
                    .resideOutsideOfPackage(GENERATED_GRAPHQL_PACKAGE)
                    .should(haveLimitedMethods)
                    .because("Interfaces should be small and focused (ISP)");

            rule.check(classes);
        }

        /**
         * <b>Width = abstract + default instance methods.</b> Counting only {@code abstract}
         * methods would let an interface hide its true surface behind {@code default} no-op
         * bodies — a default no-op is still part of the contract every caller can invoke, so it
         * counts toward ISP width exactly like an abstract method. Static and private (helper)
         * methods are excluded — they are not part of the implementable contract.
         *
         * <p>Applies to every interface under {@code ..spi..} with no exemptions.
         */
        @Test
        void spiInterfacesAreFocused() {
            ArchCondition<JavaClass> beFocused =
                    new ArchCondition<>("have at most " + MAX_SPI_METHODS + " abstract+default methods") {
                        @Override
                        public void check(JavaClass javaClass, ConditionEvents events) {
                            int methodCount = (int) javaClass.getMethods().stream()
                                    .filter(m -> !m.getModifiers().contains(JavaModifier.STATIC))
                                    .filter(m -> !m.getModifiers().contains(JavaModifier.PRIVATE))
                                    .count();

                            if (methodCount > MAX_SPI_METHODS) {
                                events.add(SimpleConditionEvent.violated(
                                        javaClass,
                                        String.format(
                                                "SPI %s has %d abstract+default methods (max %d) - split interface",
                                                javaClass.getSimpleName(), methodCount, MAX_SPI_METHODS)));
                            }
                        }
                    };

            ArchRule rule = classes()
                    .that()
                    .areInterfaces()
                    .and()
                    .resideInAPackage("..spi..")
                    .should(beFocused)
                    .allowEmptyShould(
                            false) // strict: ..spi.. is populated; an empty match would mean the rule silently stopped
                    // seeing SPIs
                    .because("SPI interfaces should be minimal");

            rule.check(classes);
        }
    }

    @Nested
    class DependencyInversionTests {

        /** ObjectProvider is a valid way to break cycles, but new usages must justify themselves below. */
        @Test
        void objectProviderUsageIsLimited() {
            Set<String> knownCycleBreakers = Set.of(
                    "AccountPreferencesService",
                    "WorkspaceActivationService",
                    "GithubLifecycleListener", // IntegrationNatsConsumer absent under the webhook runtime role
                    // (server.enabled=false) — see ADR 0008
                    "WorkspaceLifecycleService", // IntegrationNatsConsumer absent under the webhook runtime role
                    "GitHubWorkspaceProvisioningAdapter", // Lazy-loaded to break circular reference with
                    // GithubDataSyncService
                    "WorkspaceRepositoryMonitorService",
                    "ScmWorkspaceContentEraser", // IntegrationNatsConsumer absent under the webhook runtime role — the
                    // erase refreshes the scope consumer once after dropping the
                    // workspace's monitors
                    "WorkspaceSyncTargetProvider", // IntegrationNatsConsumer absent under the webhook runtime role —
                    // reconcileSyncTargetIdentity refreshes the scope consumer after a
                    // rename re-key
                    "GitLabWorkspaceInitializationService", // Optional GitLab beans gated by @ConditionalOnProperty
                    "GitLabWebhookService", // Optional GitLab beans gated by @ConditionalOnProperty
                    "GitlabDataSyncScheduler", // Optional GitLab beans gated by @ConditionalOnProperty
                    "GitLabHistoricalBackfillService", // Optional GitLab beans gated by @ConditionalOnProperty
                    "HistoricalBackfillScheduler", // Optional GitLab backfill service gated by @ConditionalOnProperty
                    "GitHubWorkspaceDataSyncTrigger", // Lazy-loads GithubDataSyncService + SyncTargetProvider to break
                    // the same circular reference WorkspaceProvisioningAdapter
                    // handled; the workspace-side trigger sits on the GitHub adapter
                    // post-SPI extraction
                    "WorkspaceScopedTables", // EntityManagerFactory is consumed transitively by
                    // HibernatePropertiesCustomizer — lazy lookup breaks the EMF<->tenancy
                    // startup cycle (see WorkspaceScopedTables javadoc)
                    "MentorChatService", // InteractiveSandboxService is part of the worker capability
                    // (DockerSandboxConfiguration, gated on the worker role); absent on non-worker
                    // pods — resolved lazily at attach time
                    "OutlineWorkspacePurgeAdapter", // OutlineWebhookRegistrar is optional (gated by
                    // @ConditionalOnProperty(hephaestus.integration.outline.enabled));
                    // the always-on purge contributor resolves it lazily so it still
                    // drops leftover documents when Outline is disabled
                    "OutlineWebhookRegistrar", // IntegrationNatsConsumer is optional (gated on
                    // hephaestus.sync.nats.enabled and the server runtime role); the
                    // registrar reconciles the scope consumer after every subscription-id
                    // change and must not require the bean
                    "SlackScopeConsumerReconciler", // same reason as OutlineWebhookRegistrar: IntegrationNatsConsumer
                    // is optional (nats/server-role gated); the Slack lifecycle
                    // reconciler must not require the bean
                    "SyncPushService", // Qualified NATS connection is optional when sync push is disabled or under
                    // specs
                    "GitlabConnectionSyncStateProvider", // Rate-limit tracker is conditional with the GitLab runtime
                    // beans
                    "OutlineConnectionSyncStateProvider", // Rate-limit tracker (OutlineRateLimitTracker) is
                    // @ConditionalOnProperty(outline.enabled) — same
                    // optional-bean break as the GitLab provider
                    "OutlineDocumentSyncService", // DocumentReviewTrigger's sole impl is
                    // @ConditionalOnProperty(hephaestus.agent.enabled); the mirror
                    // records every document signal on every runtime role and only skips
                    // the review offer where nothing could run one
                    "InstanceLlmSettingsService" // LlmSettingsAudit's sole impl is @ConditionalOnServerRole, but this
                    // service is also consumed by the ungated Workspace{Llm}Service pair
                    // (BYO gate check) which load on every runtime role
                    );

            ArchCondition<JavaField> beInKnownClass = new ArchCondition<>("be in a known cycle-breaking class") {
                @Override
                public void check(JavaField field, ConditionEvents events) {
                    String ownerName = field.getOwner().getSimpleName();
                    if (!knownCycleBreakers.contains(ownerName)) {
                        events.add(SimpleConditionEvent.violated(
                                field,
                                String.format(
                                        "NEW ObjectProvider usage in %s - add to knownCycleBreakers if intentional",
                                        field.getFullName())));
                    }
                }
            };

            ArchRule rule = fields().that()
                    .haveRawType(ObjectProvider.class)
                    .should(beInKnownClass)
                    .because("ObjectProvider usage should be limited to documented cycle-breaking cases");

            rule.check(classes);
        }
    }

    @Nested
    class LiskovSubstitutionTests {

        @Test
        void serviceMethodsDoNotDeclareGenericException() {
            ArchCondition<JavaClass> notDeclareGenericException =
                    new ArchCondition<>("not declare generic Exception in methods") {
                        @Override
                        public void check(JavaClass javaClass, ConditionEvents events) {
                            javaClass.getMethods().stream()
                                    .filter(m -> m.getOwner().equals(javaClass))
                                    .filter(m -> !m.getName().startsWith("$"))
                                    .filter(m -> !m.getName().equals("<init>"))
                                    .forEach(method -> {
                                        boolean declaresGenericException = method.getThrowsClause().stream()
                                                .anyMatch(t ->
                                                        t.getRawType().getName().equals("java.lang.Exception"));
                                        if (declaresGenericException) {
                                            events.add(SimpleConditionEvent.violated(
                                                    javaClass,
                                                    String.format(
                                                            "LSP: %s.%s declares generic Exception - use specific exceptions",
                                                            javaClass.getSimpleName(), method.getName())));
                                        }
                                    });
                        }
                    };

            ArchRule rule = classes()
                    .that()
                    .haveSimpleNameEndingWith("Service")
                    .and()
                    .areAnnotatedWith(Service.class)
                    .should(notDeclareGenericException)
                    .because("LSP: specific exceptions are required for substitutability");

            rule.check(classes);
        }

        /**
         * Scans method bytecode for instantiation of UnsupportedOperationException, which catches
         * both direct throws and throws via utility methods.
         */
        @Test
        void serviceImplementationsDoNotThrowUnsupportedOperationException() {
            ArchCondition<JavaClass> notThrowUnsupportedOperationException =
                    new ArchCondition<>("not throw UnsupportedOperationException") {
                        @Override
                        public void check(JavaClass javaClass, ConditionEvents events) {
                            javaClass.getMethods().stream()
                                    .filter(m -> m.getOwner().equals(javaClass))
                                    .filter(m -> !m.getName().startsWith("$"))
                                    .filter(m -> !m.getName().startsWith("lambda$"))
                                    .filter(m -> !m.getName().equals("<init>"))
                                    .forEach(method -> {
                                        boolean throwsUnsupported = method.getConstructorCallsFromSelf().stream()
                                                .anyMatch(call -> call.getTargetOwner()
                                                        .getName()
                                                        .equals("java.lang.UnsupportedOperationException"));

                                        if (throwsUnsupported) {
                                            events.add(SimpleConditionEvent.violated(
                                                    javaClass,
                                                    String.format(
                                                            "LSP: %s.%s throws UnsupportedOperationException - "
                                                                    + "indicates contract violation, consider interface redesign",
                                                            javaClass.getSimpleName(), method.getName())));
                                        }
                                    });
                        }
                    };

            ArchRule rule = classes()
                    .that()
                    .haveSimpleNameEndingWith("Service")
                    .and()
                    .areAnnotatedWith(Service.class)
                    .should(notThrowUnsupportedOperationException)
                    .because("LSP: implementations must honor contracts, not refuse operations");

            rule.check(classes);
        }
    }
}
