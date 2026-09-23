package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Multi-Tenancy Architecture Tests - Workspace Isolation Enforcement.
 *
 * <h2>CRITICAL: These tests prevent cross-workspace data leakage</h2>
 *
 * <p>In a multi-tenant system, every data access must be scoped to a workspace.
 * These tests enforce:
 * <ul>
 *   <li><b>Repository queries</b> - Must include workspace filtering</li>
 *   <li><b>Scheduled jobs</b> - Must iterate workspaces and set context</li>
 *   <li><b>Event listeners</b> - Must have access to workspace context</li>
 *   <li><b>Service methods</b> - Public data methods should take workspace parameter</li>
 * </ul>
 *
 * @see ArchitectureTestConstants
 */
class MultiTenancyArchitectureTest extends HephaestusArchitectureTest {

    /**
     * Package prefixes that are inherently workspace-agnostic.
     *
     * <p>The {@code integration.scm} package is the shared SCM kernel (entities, SPIs,
     * sync orchestrator). The per-vendor ETL handlers under {@code integration.<kind>} operate at
     * the external entity level (GitHub/GitLab IDs) and resolve workspace context through entity
     * relationships, so they are workspace-agnostic for the purposes of this rule.
     * Workspace filtering happens at the domain/application layer, not at the
     * integration.scm/integration ETL layer.
     */
    static final String SCM_PACKAGE = BASE_PACKAGE + ".integration.scm";

    static final String INTEGRATION_GITHUB_PACKAGE = BASE_PACKAGE + ".integration.scm.github";
    static final String INTEGRATION_GITLAB_PACKAGE = BASE_PACKAGE + ".integration.scm.gitlab";

    private static boolean isInWorkspaceAgnosticPackage(JavaClass javaClass) {
        String pkg = javaClass.getPackageName();
        return (pkg.startsWith(SCM_PACKAGE)
                || pkg.startsWith(INTEGRATION_GITHUB_PACKAGE)
                || pkg.startsWith(INTEGRATION_GITLAB_PACKAGE));
    }

    /**
     * Whether an element is gated by the instance-admin authority alone, which is cross-workspace by
     * design. Exact match, not {@code contains}: a composite like
     * {@code hasAnyAuthority('app_admin','workspace_member')} mentions app_admin but is reachable by a
     * workspace member, and a substring test would hand it this exemption. Anything composite has to
     * justify itself some other way. {@code InstanceAdminGateExemptionTest} pins this.
     */
    static final String INSTANCE_ADMIN_GATE = "hasAuthority('app_admin')";

    static boolean isInstanceAdminGated(com.tngtech.archunit.core.domain.properties.HasAnnotations<?> element) {
        return element.tryGetAnnotationOfType(org.springframework.security.access.prepost.PreAuthorize.class)
                .map(a -> INSTANCE_ADMIN_GATE.equals(a.value().trim()))
                .orElse(false);
    }

    static final Set<String> WORKSPACE_AGNOSTIC_SCHEDULERS = Set.of();

    /**
     * Repositories that are legitimately workspace-agnostic (shared infrastructure or lookup tables).
     * Prefer annotating classes with {@link WorkspaceAgnostic} directly over adding here.
     */
    static final Set<String> WORKSPACE_AGNOSTIC_REPOSITORIES = Set.of();

    /**
     * Services that are legitimately workspace-agnostic (user, system, or admin scope).
     * Prefer annotating classes with {@link WorkspaceAgnostic} directly over adding here.
     */
    static final Set<String> WORKSPACE_AGNOSTIC_SERVICES = Set.of();

    /**
     * Repository methods that are legitimately workspace-agnostic. Format: "RepositoryName.methodName".
     * Prefer annotating the method or class with {@link WorkspaceAgnostic} directly over adding here.
     */
    static final Set<String> WORKSPACE_AGNOSTIC_METHODS = Set.of();

    @Nested
    class RepositoryWorkspaceFilteringTests {

        @Test
        void observationQueriesBindWorkspaceParameter() {
            assertThat(ObservationRepository.class.isAnnotationPresent(WorkspaceAgnostic.class))
                    .isFalse();
            for (Method method : ObservationRepository.class.getDeclaredMethods()) {
                Query query = method.getAnnotation(Query.class);
                if (query == null) continue;
                assertThat(method.getParameters())
                        .as("%s must bind workspaceId", method.getName())
                        .anyMatch(parameter -> {
                            Param param = parameter.getAnnotation(Param.class);
                            return param != null && param.value().equals("workspaceId");
                        });
            }
        }

        @Test
        void queryMethodsIncludeWorkspaceFiltering() {
            ArchCondition<JavaMethod> includeWorkspaceFiltering =
                    new ArchCondition<>("include workspace filtering in @Query") {
                        @Override
                        public void check(JavaMethod method, ConditionEvents events) {
                            if (!method.isAnnotatedWith(Query.class)) {
                                return;
                            }

                            String repoName = method.getOwner().getSimpleName();
                            String methodKey = repoName + "." + method.getName();

                            // integration.scm is the inherently workspace-agnostic ETL layer
                            if (isInWorkspaceAgnosticPackage(method.getOwner())) {
                                return;
                            }

                            if (method.getOwner().isAnnotatedWith(WorkspaceAgnostic.class)) {
                                return;
                            }

                            if (WORKSPACE_AGNOSTIC_REPOSITORIES.contains(repoName)) {
                                return;
                            }

                            if (WORKSPACE_AGNOSTIC_METHODS.contains(methodKey)) {
                                return;
                            }

                            if (method.isAnnotatedWith(WorkspaceAgnostic.class)) {
                                return;
                            }

                            Query queryAnnotation = method.getAnnotationOfType(Query.class);
                            String queryValue = queryAnnotation.value();

                            boolean hasDirectWorkspaceFilter = queryValue.contains("workspaceId")
                                    || queryValue.contains("workspace.id")
                                    || queryValue.contains("workspace_id")
                                    || queryValue.contains("JOIN Workspace");

                            // Implicit workspace through repository chain:
                            // Repository -> Organization -> (Workspace.organization = Organization)
                            // Queries filtering by repository.id or repositoryId are workspace-scoped
                            // because the sync layer only processes entities for monitored repositories
                            boolean hasRepositoryFilter = queryValue.contains("repository.id")
                                    || queryValue.contains("repositoryId")
                                    || queryValue.contains("p.repository.id")
                                    || queryValue.contains("i.repository.id")
                                    || queryValue.contains("r.id");

                            // Implicit workspace through pull request chain:
                            // PullRequest -> Repository -> Organization -> (Workspace.organization = Organization)
                            // Queries filtering by pullRequest.id or pullRequestId are workspace-scoped
                            boolean hasPullRequestFilter = queryValue.contains("pullRequest.id")
                                    || queryValue.contains("pullRequestId")
                                    || queryValue.contains("prr.pullRequest.id");

                            // Implicit workspace through organization chain:
                            // Organization -> (Workspace.organization = Organization)
                            // Queries filtering by organization.id or organizationId are workspace-scoped
                            boolean hasOrganizationFilter = queryValue.contains("organization.id")
                                    || queryValue.contains("organizationId")
                                    || queryValue.contains("orgId");

                            boolean hasWorkspaceFilter = hasDirectWorkspaceFilter
                                    || hasRepositoryFilter
                                    || hasPullRequestFilter
                                    || hasOrganizationFilter;

                            if (!hasWorkspaceFilter) {
                                events.add(SimpleConditionEvent.violated(
                                        method,
                                        String.format(
                                                "MULTI-TENANCY RISK: %s.%s has @Query without workspace filtering. "
                                                        + "Query: %s... Add to WORKSPACE_AGNOSTIC_METHODS if intentional.",
                                                repoName,
                                                method.getName(),
                                                queryValue.substring(0, Math.min(80, queryValue.length())))));
                            }
                        }
                    };

            ArchRule rule = methods()
                    .that()
                    .areDeclaredInClassesThat()
                    .haveSimpleNameEndingWith("Repository")
                    .and()
                    .areDeclaredInClassesThat()
                    .resideInAPackage(BASE_PACKAGE + "..")
                    .should(includeWorkspaceFiltering)
                    .because("Repository queries must filter by workspace to prevent cross-tenant data access");

            rule.check(classes);
        }

        /** Methods like findAll() are dangerous in multi-tenant systems without a scoped alternative. */
        @Test
        void repositoriesHaveWorkspaceScopedAlternatives() {
            ArchCondition<JavaClass> haveWorkspaceScopedMethods =
                    new ArchCondition<>("have workspace-scoped query methods") {
                        @Override
                        public void check(JavaClass javaClass, ConditionEvents events) {
                            String repoName = javaClass.getSimpleName();

                            // integration.scm is the inherently workspace-agnostic ETL layer
                            if (isInWorkspaceAgnosticPackage(javaClass)) {
                                return;
                            }

                            if (javaClass.isAnnotatedWith(WorkspaceAgnostic.class)) {
                                return;
                            }

                            if (WORKSPACE_AGNOSTIC_REPOSITORIES.contains(repoName)) {
                                return;
                            }

                            boolean isSpringDataRepo = javaClass.getAllRawInterfaces().stream()
                                    .anyMatch(i -> i.getName().contains("JpaRepository")
                                            || i.getName().contains("CrudRepository"));

                            if (!isSpringDataRepo) {
                                return;
                            }

                            Set<String> methodNames = javaClass.getMethods().stream()
                                    .map(JavaMethod::getName)
                                    .collect(Collectors.toSet());

                            boolean hasWorkspaceScopedMethods = methodNames.stream()
                                    .anyMatch(name ->
                                            // Direct workspace filtering
                                            name.contains("ByWorkspace")
                                                    || name.contains("ForWorkspace")
                                                    || name.contains("InWorkspace")
                                                    || name.contains("workspaceId")
                                                    ||
                                                    // Implicit through repository chain
                                                    name.contains("ByRepository")
                                                    || name.contains("Repository_Id")
                                                    ||
                                                    // Implicit through organization chain
                                                    name.contains("ByOrganization")
                                                    || name.contains("Organization_Id")
                                                    ||
                                                    // Implicit through team chain (team.organization -> workspace)
                                                    name.contains("ByTeam")
                                                    || name.contains("Team_Id")
                                                    ||
                                                    // Implicit through pull request chain
                                                    name.contains("ByPullRequest")
                                                    || name.contains("PullRequest_Id")
                                                    ||
                                                    // Thread -> PR -> repo -> org -> workspace
                                                    name.contains("ByThread")
                                                    || name.contains("Thread_Id"));

                            // Repositories with only ID-based lookups are implicitly workspace-scoped
                            // because the ID must have been obtained from a workspace-scoped context.
                            // This covers repositories that only provide findById, findWithXyzById, etc.
                            boolean hasOnlyIdBasedMethods = javaClass.getMethods().stream()
                                    .filter(m -> !m.getName().equals("findAll")
                                            && !m.getName().equals("count")
                                            && !m.getName().equals("existsAll")
                                            && !m.getName().startsWith("save")
                                            && !m.getName().startsWith("delete")
                                            && !m.getName().startsWith("flush")
                                            && !m.getName().equals("getReferenceById")
                                            && !m.getName().equals("getById"))
                                    .allMatch(m -> m.getName().contains("ById")
                                            || m.getName().contains("AllById"));

                            boolean hasQueryWithWorkspaceFilter = javaClass.getMethods().stream()
                                    .filter(m -> m.isAnnotatedWith(Query.class))
                                    .anyMatch(m -> {
                                        Query q = m.getAnnotationOfType(Query.class);
                                        String queryValue = q.value();
                                        boolean directFilter = queryValue.contains("workspaceId")
                                                || queryValue.contains("workspace.id")
                                                || queryValue.contains("JOIN Workspace");
                                        boolean repoFilter = queryValue.contains("repository.id")
                                                || queryValue.contains("repositoryId");
                                        boolean prFilter = queryValue.contains("pullRequest.id")
                                                || queryValue.contains("pullRequestId");
                                        boolean orgFilter = queryValue.contains("organization.id")
                                                || queryValue.contains("organizationId")
                                                || queryValue.contains("orgId");
                                        return directFilter || repoFilter || prFilter || orgFilter;
                                    });

                            if (!hasWorkspaceScopedMethods && !hasQueryWithWorkspaceFilter && !hasOnlyIdBasedMethods) {
                                events.add(SimpleConditionEvent.violated(
                                        javaClass,
                                        String.format(
                                                "MULTI-TENANCY AUDIT: %s has no workspace-scoped query methods. "
                                                        + "Consider adding findByWorkspaceId or similar methods.",
                                                repoName)));
                            }
                        }
                    };

            ArchRule rule = classes()
                    .that()
                    .haveSimpleNameEndingWith("Repository")
                    .and()
                    .resideInAPackage(BASE_PACKAGE + "..")
                    .and()
                    .areInterfaces()
                    .should(haveWorkspaceScopedMethods)
                    .because("Repositories should provide workspace-scoped query methods");

            rule.check(classes);
        }
    }

    @Nested
    class ScheduledJobContextTests {

        /** Scheduled jobs run outside any request context, so they must set up their own workspace iteration. */
        @Test
        void scheduledClassesHaveWorkspaceIterationDependencies() {
            ArchCondition<JavaClass> haveWorkspaceIterationCapability =
                    new ArchCondition<>("have workspace iteration or context dependencies") {
                        @Override
                        public void check(JavaClass javaClass, ConditionEvents events) {
                            boolean hasScheduledMethods =
                                    javaClass.getMethods().stream().anyMatch(m -> m.isAnnotatedWith(Scheduled.class));

                            if (!hasScheduledMethods) {
                                return;
                            }

                            if (javaClass.isAnnotatedWith(WorkspaceAgnostic.class)) {
                                return;
                            }

                            // Skip if EVERY @Scheduled method declares the bypass itself. This is the tighter form of
                            // the type-level annotation and the one to prefer: a scheduler whose cron fan-outs are
                            // cross-tenant but whose other (single-workspace) entry points are not must NOT blanket
                            // the whole class in a bypass — that would silently disarm WorkspaceStatementInspector on
                            // the scoped methods too (see OutlineDocumentSyncScheduler).
                            boolean everyScheduledMethodDeclaresBypass = javaClass.getMethods().stream()
                                    .filter(m -> m.isAnnotatedWith(Scheduled.class))
                                    .allMatch(m -> m.isAnnotatedWith(WorkspaceAgnostic.class));
                            if (everyScheduledMethodDeclaresBypass) {
                                return;
                            }

                            if (WORKSPACE_AGNOSTIC_SCHEDULERS.contains(javaClass.getSimpleName())) {
                                return;
                            }

                            Set<String> dependencies = javaClass.getFields().stream()
                                    .map(f -> f.getRawType().getSimpleName())
                                    .collect(Collectors.toSet());

                            javaClass
                                    .getConstructors()
                                    .forEach(c ->
                                            c.getRawParameterTypes().forEach(p -> dependencies.add(p.getSimpleName())));

                            boolean hasWorkspaceAwareDependency = dependencies.contains("SyncTargetProvider")
                                    || dependencies.contains("SyncContextProvider")
                                    || dependencies.contains("WorkspaceRepository")
                                    || dependencies.contains("WorkspaceService")
                                    ||
                                    // Cache eviction schedulers are workspace-agnostic by design
                                    javaClass.getSimpleName().contains("Cache");

                            if (!hasWorkspaceAwareDependency) {
                                events.add(SimpleConditionEvent.violated(
                                        javaClass,
                                        String.format(
                                                "SCHEDULED JOB CONTEXT: %s has @Scheduled methods but no workspace "
                                                        + "iteration dependencies. Add SyncTargetProvider or WorkspaceRepository "
                                                        + "to iterate workspaces and set context, or add to WORKSPACE_AGNOSTIC_SCHEDULERS if truly system-wide.",
                                                javaClass.getSimpleName())));
                            }
                        }
                    };

            ArchRule rule = classes()
                    .that()
                    .resideInAPackage(BASE_PACKAGE + "..")
                    .should(haveWorkspaceIterationCapability)
                    .because("Scheduled jobs must iterate workspaces and set context");

            rule.check(classes);
        }

        @Test
        void scheduledMethodsDontBypassWorkspaceContext() {
            ArchCondition<JavaMethod> notDirectlyAccessGlobalRepositoryMethods =
                    new ArchCondition<>("not directly access repository methods without workspace context") {
                        @Override
                        public void check(JavaMethod method, ConditionEvents events) {
                            if (!method.isAnnotatedWith(Scheduled.class)) {
                                return;
                            }

                            // Heuristic: scan the method body for direct unscoped repository calls
                            Set<String> calledMethods = method.getMethodCallsFromSelf().stream()
                                    .map(call -> call.getTarget().getName())
                                    .collect(Collectors.toSet());

                            Set<String> dangerousMethods = Set.of("findAll", "findById", "count", "existsById");

                            Set<String> dangerousCalls = calledMethods.stream()
                                    .filter(dangerousMethods::contains)
                                    .collect(Collectors.toSet());

                            // If the method also calls workspace-aware methods, it's likely safe
                            boolean hasWorkspaceAwareCalls = calledMethods.stream()
                                    .anyMatch(name -> name.contains("Workspace")
                                            || name.contains("workspace")
                                            || name.contains("setContext")
                                            || name.contains("SyncSession")
                                            || name.contains("getWorkspace"));

                            if (!dangerousCalls.isEmpty() && !hasWorkspaceAwareCalls) {
                                events.add(SimpleConditionEvent.violated(
                                        method,
                                        String.format(
                                                "SCHEDULED JOB BYPASS: %s.%s calls %s without apparent workspace context. "
                                                        + "Ensure workspace iteration or use workspace-scoped repository methods.",
                                                method.getOwner().getSimpleName(), method.getName(), dangerousCalls)));
                            }
                        }
                    };

            ArchRule rule = methods()
                    .that()
                    .areDeclaredInClassesThat()
                    .resideInAPackage(BASE_PACKAGE + "..")
                    .should(notDirectlyAccessGlobalRepositoryMethods)
                    .because("Scheduled jobs must not bypass workspace context");

            rule.check(classes);
        }
    }

    @Nested
    class EventListenerContextTests {

        @Test
        void eventListenersReceiveWorkspaceContext() {
            ArchCondition<JavaMethod> handleEventsWithWorkspaceContext =
                    new ArchCondition<>("handle events that carry workspace context") {
                        @Override
                        public void check(JavaMethod method, ConditionEvents events) {
                            boolean isEventListener = method.isAnnotatedWith(TransactionalEventListener.class)
                                    || method.isAnnotatedWith(EventListener.class);

                            if (!isEventListener) {
                                return;
                            }

                            // Domain events must carry a workspaceId or an entity that resolves one
                            method.getRawParameterTypes().forEach(paramType -> {
                                String paramTypeName = paramType.getSimpleName();

                                // Known event types that carry workspace context through entity relationships
                                Set<String> workspaceAwareEventPrefixes = Set.of(
                                        "ScmDomainEvent", // Our domain events carry repository which has workspace
                                        "PullRequest", // Through repository.organization.workspaceId
                                        "Issue", // Through repository.organization.workspaceId
                                        "Discussion", // Through repository.organization.workspaceId
                                        "Review", // Through PR
                                        "Comment", // Through PR -> repository.organization.workspaceId
                                        "Commit", // Through repository.organization.workspaceId
                                        "Project", // Through organization.workspaceId
                                        "PracticeDetectionCompletedEvent", // carries workspaceId directly (mentor cache
                                        // eviction)
                                        "PracticeDetectionDeliveredEvent", // carries workspaceId directly
                                        // (conversational routing)
                                        "ConversationFeedbackPreparedEvent", // carries workspaceId directly (Slack
                                        // nudge)
                                        "ApprovedFeedbackReadyEvent", // carries workspaceId directly
                                        "BotCommand", // BotCommandReceivedEvent carries repositoryId → workspace
                                        "LeaderboardDigestReadyEvent", // Carries workspaceId for the vendor-publish
                                        // fan-out
                                        "WorkspaceCreatedEvent", // Carries workspaceId + kind
                                        // ConnectionLifecycleEvent.Activated / .Deactivated carry workspaceId directly
                                        // (published from ConnectionService.transition; consumed by vendor adapters).
                                        "Activated",
                                        "Deactivated",
                                        "WorkspaceScheduleChangedEvent", // Carries workspaceId for leaderboard
                                        // reschedule
                                        "SyncStateChangedEvent", // Carries workspaceId directly
                                        "RepositoryAboutToBeDeletedEvent", // Carries repositoryId → workspace via FK
                                        "ScmMirrorErasedEvent", // Carries workspaceId directly (SCM disconnect/purge
                                        // erase; derived-row listeners in practices + activity)
                                        "ApplicationReadyEvent", // Spring lifecycle, no workspace needed
                                        // Control-plane lifecycle: a worker's session closed, so the Git operations
                                        // dispatched to it fail; no workspace is party to the event.
                                        "WorkerDisconnectedEvent",
                                        "ContextRefreshedEvent", // Spring lifecycle, no workspace needed
                                        "WorkspacesInitializedEvent", // Startup lifecycle, signals all workspaces ready
                                        // core.auth (ADR 0017): authentication is USER/SYSTEM-scoped, never
                                        // workspace-scoped (same rationale as the @WorkspaceAgnostic auth controllers
                                        // exempted below). These Spring Security login events drive auth.login metrics
                                        // in AuthLoginEventMetrics and carry no workspace by design.
                                        "InteractiveAuthenticationSuccessEvent",
                                        "AbstractAuthenticationFailureEvent",
                                        // Carries workspaceId + collectionId. An in-module after-commit hop: the
                                        // Outline collection resume must not kick its async sync until the ENABLED
                                        // write is committed, or the sync reads PAUSED and no-ops.
                                        "OutlineCollectionResumedEvent");

                                boolean isWorkspaceAware =
                                        workspaceAwareEventPrefixes.stream().anyMatch(paramTypeName::contains);

                                if (!isWorkspaceAware && !paramTypeName.equals("Object")) {
                                    events.add(SimpleConditionEvent.violated(
                                            method,
                                            String.format(
                                                    "EVENT LISTENER CONTEXT: %s.%s handles %s - verify it carries workspace context. "
                                                            + "Events should include workspaceId or entity with workspace relationship.",
                                                    method.getOwner().getSimpleName(),
                                                    method.getName(),
                                                    paramTypeName)));
                                }
                            });
                        }
                    };

            ArchRule rule = methods()
                    .that()
                    .areDeclaredInClassesThat()
                    .resideInAPackage(BASE_PACKAGE + "..")
                    .should(handleEventsWithWorkspaceContext)
                    .because("Event listeners must receive workspace context through events");

            rule.check(classes);
        }

        /**
         * Workspace context is preserved via entity relationships in the event payload (e.g., Comment ->
         * PullRequest -> Repository -> Organization -> workspaceId).
         */
        static final Set<String> ASYNC_LISTENERS_WITH_PAYLOAD_CONTEXT = Set.of(
                // ActivityEventListener handles CommentCreated, ReviewSubmitted events which carry full entity graphs
                "ActivityEventListener",
                // AgentJobEventListener handles ScmDomainEvent.PullRequest{Created,Ready,Synchronized,
                // Merged,Closed}/ReviewSubmitted whose EventContext carries the originating repository →
                // workspaceId is resolved per-event
                "AgentJobEventListener",
                // IssueAgentJobEventListener handles ScmDomainEvent.Issue{Created,Labeled} whose EventContext
                // carries the originating repository → workspaceId is resolved per-event (mirrors the PR listener)
                "IssueAgentJobEventListener",
                // MentorContextInvalidator handles ScmDomainEvent.{PullRequest,Issue,Review}* whose
                // EventContext carries the originating repository → workspaceId is resolved per-event
                "MentorContextInvalidator",
                // GitHubProjectActivityListener handles GitHubProjectEvent payloads whose EventContext
                // carries scopeId (the originating workspace) — same payload-carries-context contract
                "GitHubProjectActivityListener");

        /** When @Async is combined with @TransactionalEventListener, MDC/ThreadLocal context is lost unless propagated. */
        @Test
        void asyncEventListenersPropagateContext() {
            ArchCondition<JavaClass> propagateContextInAsyncListeners =
                    new ArchCondition<>("propagate workspace context in async event listeners") {
                        @Override
                        public void check(JavaClass javaClass, ConditionEvents events) {
                            boolean hasAsyncEventListeners = javaClass.getMethods().stream()
                                    .anyMatch(m -> m.isAnnotatedWith(Async.class)
                                            && (m.isAnnotatedWith(TransactionalEventListener.class)
                                                    || m.isAnnotatedWith(EventListener.class)));

                            if (!hasAsyncEventListeners) {
                                return;
                            }

                            if (ASYNC_LISTENERS_WITH_PAYLOAD_CONTEXT.contains(javaClass.getSimpleName())) {
                                return;
                            }

                            Set<String> dependencies = javaClass.getFields().stream()
                                    .map(f -> f.getRawType().getSimpleName())
                                    .collect(Collectors.toSet());

                            javaClass
                                    .getConstructors()
                                    .forEach(c ->
                                            c.getRawParameterTypes().forEach(p -> dependencies.add(p.getSimpleName())));

                            boolean hasRepositoryDependency =
                                    dependencies.stream().anyMatch(d -> d.endsWith("Repository"));

                            // Events with full entity snapshots (like ScmDomainEvent payloads) don't need
                            // context propagation - they carry all needed data
                            boolean usesPayloadEvents = javaClass.getMethods().stream()
                                    .filter(m -> m.isAnnotatedWith(TransactionalEventListener.class))
                                    .flatMap(m -> m.getRawParameterTypes().stream())
                                    .anyMatch(p -> p.getSimpleName().contains("ScmDomainEvent")
                                            || p.getSimpleName().contains("Event"));

                            if (hasRepositoryDependency && !usesPayloadEvents) {
                                events.add(SimpleConditionEvent.violated(
                                        javaClass,
                                        String.format(
                                                "ASYNC CONTEXT RISK: %s has @Async event listeners with repository access. "
                                                        + "Ensure workspace context is propagated via event payload or MDC propagation.",
                                                javaClass.getSimpleName())));
                            }
                        }
                    };

            ArchRule rule = classes()
                    .that()
                    .resideInAPackage(BASE_PACKAGE + "..")
                    .should(propagateContextInAsyncListeners)
                    .because("Async event listeners must not lose workspace context");

            rule.check(classes);
        }
    }

    @Nested
    class ServiceWorkspaceAwarenessTests {

        @Test
        void serviceListMethodsAreWorkspaceScoped() {
            ArchCondition<JavaMethod> beWorkspaceScopedIfReturningList =
                    new ArchCondition<>("be workspace-scoped if returning a list of entities") {
                        @Override
                        public void check(JavaMethod method, ConditionEvents events) {
                            if (!method.getModifiers().contains(JavaModifier.PUBLIC)) {
                                return;
                            }

                            String returnType = method.getRawReturnType().getName();
                            boolean returnsList = returnType.contains("List")
                                    || returnType.contains("Set")
                                    || returnType.contains("Collection")
                                    || returnType.contains("Page");

                            if (!returnsList) {
                                return;
                            }

                            String methodName = method.getName();
                            boolean isWorkspaceAware = methodName.contains("Workspace")
                                    || methodName.contains("workspace")
                                    || methodName.contains("ByTeam")
                                    || // Team implies workspace scope
                                    methodName.contains("ForUser"); // User in workspace context

                            boolean hasWorkspaceParam = method.getRawParameterTypes().stream()
                                    .anyMatch(
                                            p -> p.getSimpleName().contains("WorkspaceContext")
                                                    || p.getSimpleName().contains("Workspace")
                                                    || p.getName().contains("Long") // workspaceId as Long
                                            );

                            if (!isWorkspaceAware && !hasWorkspaceParam) {
                                // This is informational - services may have internal methods
                                // that are called with workspace context already established
                                String ownerName = method.getOwner().getSimpleName();

                                if (WORKSPACE_AGNOSTIC_SERVICES.contains(ownerName)) {
                                    return;
                                }

                                if (method.getOwner().isAnnotatedWith(WorkspaceAgnostic.class)) {
                                    return;
                                }

                                if (methodName.startsWith("get") || methodName.startsWith("find")) {
                                    events.add(SimpleConditionEvent.violated(
                                            method,
                                            String.format(
                                                    "SERVICE WORKSPACE RISK: %s.%s returns a list but has no obvious workspace parameter. "
                                                            + "Add workspace parameter or document that scoping is applied internally.",
                                                    ownerName, methodName)));
                                }
                            }
                        }
                    };

            ArchRule rule = methods()
                    .that()
                    .areDeclaredInClassesThat()
                    .haveSimpleNameEndingWith("Service")
                    .and()
                    .areDeclaredInClassesThat()
                    .areAnnotatedWith(Service.class)
                    .should(beWorkspaceScopedIfReturningList)
                    .because("Services returning lists should be workspace-scoped");

            rule.check(classes);
        }
    }

    @Nested
    class ControllerWorkspaceContextTests {

        @Test
        void dataEndpointsReceiveWorkspaceContext() {
            ArchCondition<JavaMethod> haveWorkspaceContextForDataEndpoints =
                    new ArchCondition<>("have WorkspaceContext for data endpoints") {
                        @Override
                        public void check(JavaMethod method, ConditionEvents events) {
                            boolean hasHttpMapping = method.isAnnotatedWith(GetMapping.class)
                                    || method.isAnnotatedWith(PostMapping.class)
                                    || method.isAnnotatedWith(PutMapping.class)
                                    || method.isAnnotatedWith(DeleteMapping.class)
                                    || method.isAnnotatedWith(PatchMapping.class);

                            if (!hasHttpMapping) {
                                return;
                            }

                            String controllerName = method.getOwner().getSimpleName();
                            String methodName = method.getName();

                            if (controllerName.contains("Health")
                                    || controllerName.contains("Status")
                                    || controllerName.contains("Actuator")) {
                                return;
                            }

                            // Skip user account operations - these are USER-scoped, not WORKSPACE-scoped.
                            // Users can access their account settings regardless of workspace context.
                            if (controllerName.contains("Account")
                                    || controllerName.contains("FeatureFlag")
                                    || controllerName.contains("IdentityProvider")) {
                                return;
                            }

                            // Skip the core.auth module (ADR 0017): authentication / session / OIDC
                            // discovery endpoints are USER- or SYSTEM-scoped by definition, never
                            // workspace-scoped. Login (AuthBegin), session lifecycle (AuthLifecycle),
                            // session inventory (SessionWeb), and OIDC discovery (WellKnown) all
                            // operate outside any single workspace. The module is annotated
                            // @WorkspaceAgnostic at the package level.
                            if (method.getOwner().getPackageName().startsWith("de.tum.cit.aet.hephaestus.core.auth")) {
                                return;
                            }

                            // Skip instance-admin endpoints: an endpoint gated by the instance authority is
                            // cross-workspace BY DEFINITION (that is what an instance admin is for), so demanding
                            // a WorkspaceContext on it is backwards. This is keyed on the security annotation
                            // rather than a controller name so the exemption states its own reason. Cross-workspace
                            // reads behind it still need @WorkspaceAgnostic on the repository.
                            if (isInstanceAdminGated(method.getOwner()) || isInstanceAdminGated(method)) {
                                return;
                            }

                            // Skip workspace registry operations - these are ADMIN operations that happen
                            // BEFORE a workspace context exists (creating/listing workspaces).
                            if (controllerName.contains("WorkspaceRegistry")) {
                                return;
                            }

                            // Same exemption applies to vendor-specific preflight controllers under
                            // /workspaces/<kind>/* — they validate PATs / list discoverable scopes
                            // BEFORE a workspace exists, so there's no workspace context to enforce.
                            if (controllerName.equals("GitLabPreflightController")) {
                                return;
                            }

                            // Skip webhook ingress controllers — they receive unauthenticated vendor
                            // deliveries. Workspace context is derived from the verified payload AFTER
                            // the WebhookSignatureVerifier path, never from the inbound HTTP request.
                            //
                            // Same exemption applies to OAuthCallbackController — the vendor browser
                            // redirect is unauthenticated; workspace identity is decoded from the
                            // HMAC-signed state parameter, never from the inbound request URL.
                            if (controllerName.equals("WebhookController")
                                    || controllerName.endsWith("WebhookController")) {
                                return;
                            }
                            if (controllerName.equals("OAuthCallbackController")) {
                                return;
                            }

                            // Skip explicitly global endpoints that aggregate across all workspaces
                            if (methodName.equals("listGlobalContributors")) {
                                return;
                            }

                            boolean hasWorkspaceContext = method.getRawParameterTypes().stream()
                                    .anyMatch(p -> p.getSimpleName().equals("WorkspaceContext"));

                            boolean hasWorkspaceSecurityAnnotation = method.getAnnotations().stream()
                                            .anyMatch(a -> a.getRawType()
                                                            .getSimpleName()
                                                            .contains("Workspace")
                                                    || a.getRawType()
                                                            .getSimpleName()
                                                            .contains("Ensure")
                                                    || a.getRawType()
                                                            .getSimpleName()
                                                            .contains("Require"))
                                    || method.getOwner().getAnnotations().stream()
                                            .anyMatch(a -> a.getRawType()
                                                            .getSimpleName()
                                                            .contains("Workspace")
                                                    || a.getRawType()
                                                            .getSimpleName()
                                                            .contains("Ensure"));

                            if (!hasWorkspaceContext && !hasWorkspaceSecurityAnnotation) {
                                events.add(SimpleConditionEvent.violated(
                                        method,
                                        String.format(
                                                "CONTROLLER CONTEXT: %s.%s is a data endpoint without WorkspaceContext parameter "
                                                        + "or workspace security annotation. Add WorkspaceContext parameter or appropriate annotation.",
                                                controllerName, methodName)));
                            }
                        }
                    };

            ArchRule rule = methods()
                    .that()
                    .areDeclaredInClassesThat()
                    .areAnnotatedWith(RestController.class)
                    .and()
                    .arePublic()
                    .should(haveWorkspaceContextForDataEndpoints)
                    .because("All data endpoints must have workspace context");

            rule.check(classes);
        }
    }
}
