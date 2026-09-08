package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.*;
import static de.tum.cit.aet.hephaestus.architecture.conditions.HephaestusConditions.*;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * Data Isolation Architecture Tests - Prevents Cross-Workspace Data Access.
 *
 * <h2>CRITICAL: These tests are the last line of defense against data leakage</h2>
 *
 * <p>These tests enforce patterns that prevent accidental cross-workspace data access:
 * <ul>
 *   <li><b>Entity workspace relationships</b> - Entities should have workspace path</li>
 *   <li><b>Query result mapping</b> - Verify DTOs include workspace context</li>
 *   <li><b>API response boundaries</b> - Ensure responses don't leak cross-workspace data</li>
 * </ul>
 *
 * @see MultiTenancyArchitectureTest for runtime context enforcement
 * @see ArchitectureTestConstants
 */
class DataIsolationArchitectureTest extends HephaestusArchitectureTest {

    /**
     * Entity types that are workspace-scoped through their relationship chain.
     * Format: EntityName -> path to workspace (for documentation).
     */
    private static final Set<String> WORKSPACE_SCOPED_ENTITIES = Set.of(
            // Direct workspace relationship
            "ActivityEvent", // has Workspace field
            "RepositoryToMonitor", // has Workspace field
            "WorkspaceMembership", // has Workspace field
            "ChatThread", // has Workspace field
            // Through repository -> Workspace.organization (JOIN)
            "PullRequest",
            "Issue",
            "PullRequestReview",
            "PullRequestReviewComment",
            "PullRequestReviewThread",
            "IssueComment",
            "Label",
            "Milestone",
            // Through Workspace.organization (JOIN)
            "Repository",
            "Team",
            "TeamMembership",
            // Practice catalog entities
            "Practice", // has direct Workspace field
            "Observation", // through Practice.workspace
            // Through chat thread -> workspace
            "ChatMessage", // through ChatThread.workspace
            "ChatMessageVote", // through ChatMessage (via messageId) -> ChatThread.workspace
            // Through Workspace.organization (ID-based relationship via JOIN)
            "OrganizationMembership", // organizationId -> Workspace.organization
            // Through project owner (ID-based: ownerId -> Organization/Repository/User)
            "Project", // ownerId -> Organization/Repository -> Workspace.organization
            "ProjectItem", // through Project.ownerId OR issue -> Repository
            "ProjectField", // through Project.ownerId
            "ProjectFieldValue", // through ProjectItem.project OR ProjectField.project
            // Through repository (commit -> Repository -> Organization <- Workspace)
            "Commit", // through Repository -> Organization <- Workspace
            "CommitFileChange", // through Commit -> Repository -> Organization <- Workspace
            // Through repository (discussion -> Repository -> Organization <- Workspace)
            "Discussion", // through Repository -> Organization <- Workspace
            "DiscussionCategory", // through Repository -> Organization <- Workspace
            "DiscussionComment", // through Discussion -> Repository -> Organization <- Workspace
            // Unified integration framework
            "Connection", // has direct Workspace field
            "ConnectionAudit", // through Connection.workspace
            // Feedback synthesis seam — scoped via a raw workspace_id scalar (cross-module
            // FK to avoid a Modulith cycle, mirrors Observation.agentJobId).
            "Feedback", // direct workspace_id scalar column (sfk_feedback_workspace)
            "FeedbackObservation", // through Feedback.workspace_id (and finding -> Practice.workspace)
            "FeedbackPlacement", // through Feedback.workspace_id
            // Slack integration — every table carries a direct workspace_id scalar and is therefore
            // auto-classified scoped by WorkspaceScopedTables (absent from GLOBAL_TABLES). Listed here
            // so a future refactor that drops the column trips slackEntitiesCarryDirectWorkspaceId().
            "SlackMessage", // direct workspace_id scalar
            "SlackThread", // direct workspace_id scalar
            "SlackMonitoredChannel", // direct workspace_id scalar
            "MentorSlackThread", // direct workspace_id scalar
            // Grant allowlist: workspace_id lives inside the composite @EmbeddedId, so the direct-field
            // probe below cannot see it. Scoped, not global — the row names the tenant it grants.
            "LlmModelWorkspaceGrant");

    private static final Set<String> GLOBAL_ENTITIES = Set.of(
            "User", // Users can belong to multiple workspaces
            "UserPreferences", // Belongs to User which is global
            "Organization", // Synced from GitHub, workspace is set separately
            "Workspace", // Is the tenant root
            "WorkspaceSlugHistory", // Tracks workspace slug changes
            "IssueType", // GitHub issue types are workspace-scoped through issue
            "IdentityProvider", // Global provider instances (e.g., github.com, gitlab.com)
            "LlmConnection", // Instance-owned provider connection; global, not tenant-scoped
            "LlmModel", // Instance-curated model behind a connection; global
            "LlmModelPrice", // Instance model price history; global pricing authority
            "InstanceLlmSettings", // Instance LLM settings singleton (egress allowlist + BYO enable + default policy);
            // global
            "CuratedPracticeOverride", // What an instance admin said about a shipped practice; global
            "CuratedGroupOverride", // What an instance admin said about a shipped group; global
            "FxRate", // ECB daily reference rate for display-only conversion; a world fact, not tenant data
            "WorkerTokenDenylist", // Fleet-wide JWT revocation; worker JWTs are not workspace-scoped
            // core.auth (ADR 0017) — identity is user/system-scoped, not workspace-scoped.
            // Account ↔ Workspace association lives on WorkspaceMembership, not on these rows.
            "Account", // Hephaestus-native principal; spans workspaces
            "IdentityLink", // Federated-login association; user-scoped
            "AccountFeature", // Per-account feature opt-ins
            "AuthEvent", // Append-only auth audit; references workspace optionally, not scoped by it
            "IssuedJwt", // JWT revocation list; account-scoped
            "JwtSigningKey", // System-wide signing keys
            "AccountExport", // GDPR Art. 20 self-service export; account-scoped, spans workspaces
            "LoginProvider", // Instance-scoped OAuth login provider (sign-in option); not workspace-scoped
            "ConsentDecision", // Account-scoped consent evidence; spans workspaces
            "ConsentNotice", // System-wide archive of the exact notices presented to accounts
            "WorkerRegistry", // Fleet-wide worker liveness/capacity registry (#1138); not workspace-scoped
            "InstanceSettings" // Singleton instance-wide operator settings (silent-mode brake, #1386)
            );

    // ENTITY WORKSPACE RELATIONSHIPS

    @Nested
    class EntityWorkspaceRelationshipTests {

        /**
         * Every entity that contains business data must be traceable to a workspace either
         * directly (workspace field) or through relationships (repository.organization.workspaceId).
         */
        @Test
        void entitiesHaveWorkspaceRelationshipPath() {
            ArchCondition<JavaClass> haveWorkspacePath =
                    new ArchCondition<>("have a path to workspace (direct or through relationships)") {
                        @Override
                        public void check(JavaClass javaClass, ConditionEvents events) {
                            String entityName = javaClass.getSimpleName();

                            if (GLOBAL_ENTITIES.contains(entityName)) {
                                return;
                            }

                            if (WORKSPACE_SCOPED_ENTITIES.contains(entityName)) {
                                return;
                            }

                            boolean hasDirectWorkspace = javaClass.getFields().stream()
                                    .anyMatch(
                                            f -> f.getRawType().getSimpleName().equals("Workspace")
                                                    || f.getName().equals("workspaceId"));

                            Set<String> fieldTypes = javaClass.getFields().stream()
                                    .map(f -> f.getRawType().getSimpleName())
                                    .collect(Collectors.toSet());

                            boolean hasWorkspaceScopedParent = fieldTypes.stream()
                                    .anyMatch(t -> t.equals("Repository")
                                            || t.equals("Organization")
                                            || t.equals("PullRequest")
                                            || t.equals("Issue")
                                            || t.equals("Team")
                                            || WORKSPACE_SCOPED_ENTITIES.contains(t));

                            if (!hasDirectWorkspace && !hasWorkspaceScopedParent) {
                                events.add(SimpleConditionEvent.violated(
                                        javaClass,
                                        String.format(
                                                "ENTITY ISOLATION: %s is a JPA entity with no apparent workspace relationship. "
                                                        + "Add to WORKSPACE_SCOPED_ENTITIES with relationship path, "
                                                        + "or add to GLOBAL_ENTITIES if intentionally global.",
                                                entityName)));
                            }
                        }
                    };

            ArchRule rule = classes()
                    .that()
                    .areAnnotatedWith(jakarta.persistence.Entity.class)
                    .and()
                    .resideInAPackage(BASE_PACKAGE + "..")
                    .should(haveWorkspacePath)
                    .because("All business entities must be traceable to a workspace for data isolation");

            rule.check(classes);
        }

        /**
         * Targeted tenancy proof for the Slack integration tables.
         *
         * <p>The four {@code slack_*}/{@code mentor_slack_thread} entities are workspace-scoped by a direct
         * {@code workspace_id} scalar column (no FK chain); each must resolve to a real {@code workspaceId}
         * field so {@code WorkspaceScopedTables} classifies it scoped and the {@code StatementInspector}
         * rides a tenancy predicate on every query.
         */
        @Test
        void slackEntitiesCarryDirectWorkspaceId() {
            Set<String> slackEntities =
                    Set.of("SlackMessage", "SlackThread", "SlackMonitoredChannel", "MentorSlackThread");
            ArchCondition<JavaClass> haveWorkspaceIdField = new ArchCondition<>("carry a direct workspaceId field") {
                @Override
                public void check(JavaClass javaClass, ConditionEvents events) {
                    if (!slackEntities.contains(javaClass.getSimpleName())) {
                        return;
                    }
                    boolean hasWorkspaceId = javaClass.getFields().stream()
                            .anyMatch(f -> f.getName().equals("workspaceId"));
                    if (!hasWorkspaceId) {
                        events.add(SimpleConditionEvent.violated(
                                javaClass,
                                String.format(
                                        "SLACK TENANCY: %s must carry a direct workspaceId field so it stays workspace-scoped. "
                                                + "Removing it silently un-scopes Slack PII across tenants.",
                                        javaClass.getSimpleName())));
                    }
                }
            };

            ArchRule rule = classes()
                    .that()
                    .areAnnotatedWith(jakarta.persistence.Entity.class)
                    .and()
                    .resideInAPackage(BASE_PACKAGE + "..")
                    .should(haveWorkspaceIdField)
                    .because("Slack tables hold PII and must be workspace-scoped by a direct workspace_id column");

            rule.check(classes);
        }

        @Test
        void directWorkspaceRelationshipsNotNullable() {
            ArchRule rule = fields().that()
                    .areDeclaredInClassesThat()
                    .areAnnotatedWith(jakarta.persistence.Entity.class)
                    .should(beNotNullableIfWorkspaceType())
                    .because("Workspace relationships should be required to prevent orphaned data");

            rule.check(classes);
        }
    }

    // DTO WORKSPACE CONTEXT

    @Nested
    class DtoWorkspaceContextTests {

        @Test
        void dtosDoNotExposeCrossWorkspaceReferences() {
            ArchCondition<JavaClass> notExposeCrossWorkspaceData =
                    new ArchCondition<>("not expose fields that could leak cross-workspace data") {
                        @Override
                        public void check(JavaClass javaClass, ConditionEvents events) {
                            Set<String> fieldTypes = javaClass.getFields().stream()
                                    .map(f -> f.getRawType().getSimpleName())
                                    .collect(Collectors.toSet());

                            // User leaks a user's other-workspace data; Organization spans all its workspaces.
                            Set<String> dangerousTypes = Set.of("User", "Organization");

                            Set<String> exposedDangerousTypes = fieldTypes.stream()
                                    .filter(dangerousTypes::contains)
                                    .collect(Collectors.toSet());

                            // Exception: UserDTO, AuthorDTO are safe (workspace-filtered projections)
                            boolean usesSafeProjections = javaClass.getFields().stream()
                                    .map(f -> f.getRawType().getSimpleName())
                                    .allMatch(t -> !dangerousTypes.contains(t)
                                            || t.endsWith("DTO")
                                            || t.endsWith("Dto")
                                            || t.endsWith("Data")
                                            || t.endsWith("Info"));

                            if (!exposedDangerousTypes.isEmpty() && !usesSafeProjections) {
                                events.add(SimpleConditionEvent.violated(
                                        javaClass,
                                        String.format(
                                                "DTO LEAK RISK: %s exposes %s directly. Use projection DTOs "
                                                        + "(e.g., UserDTO, AuthorDTO) instead to prevent cross-workspace data leakage.",
                                                javaClass.getSimpleName(), exposedDangerousTypes)));
                            }
                        }
                    };

            ArchRule rule = classes()
                    .that()
                    .haveSimpleNameEndingWith("DTO")
                    .or()
                    .haveSimpleNameEndingWith("Dto")
                    .and()
                    .resideInAPackage(BASE_PACKAGE + "..")
                    .should(notExposeCrossWorkspaceData)
                    .because("DTOs should use projections to prevent cross-workspace data leakage");

            rule.check(classes);
        }
    }

    // REPOSITORY RETURN TYPE SAFETY

    @Nested
    class RepositoryReturnTypeSafetyTests {

        @Test
        void entityReturningMethodsAreWorkspaceScoped() {
            ArchCondition<JavaMethod> beWorkspaceScopedIfReturningEntity =
                    new ArchCondition<>("be workspace-scoped if returning entity types") {
                        @Override
                        public void check(JavaMethod method, ConditionEvents events) {
                            String methodName = method.getName();
                            String repoName = method.getOwner().getSimpleName();

                            // Skip standard JpaRepository methods (we can't change those)
                            Set<String> standardMethods = Set.of(
                                    "findById",
                                    "findAll",
                                    "findAllById",
                                    "save",
                                    "saveAll",
                                    "delete",
                                    "deleteById",
                                    "deleteAll",
                                    "count",
                                    "existsById");
                            if (standardMethods.contains(methodName)) {
                                return;
                            }

                            if (MultiTenancyArchitectureTest.WORKSPACE_AGNOSTIC_REPOSITORIES.contains(repoName)) {
                                return;
                            }

                            String methodKey = repoName + "." + methodName;
                            if (MultiTenancyArchitectureTest.WORKSPACE_AGNOSTIC_METHODS.contains(methodKey)) {
                                return;
                            }

                            if (method.isAnnotatedWith(WorkspaceAgnostic.class)) {
                                return;
                            }

                            if (method.getOwner().isAnnotatedWith(WorkspaceAgnostic.class)) {
                                return;
                            }

                            String returnType = method.getRawReturnType().getSimpleName();
                            boolean returnsEntity = WORKSPACE_SCOPED_ENTITIES.stream()
                                    .anyMatch(e -> returnType.equals(e)
                                            || returnType.contains("List<" + e)
                                            || returnType.contains("Optional<" + e)
                                            || returnType.contains("Set<" + e));

                            if (!returnsEntity) {
                                return;
                            }

                            boolean isWorkspaceScoped = methodName.contains("Workspace")
                                    || methodName.contains("workspace")
                                    || methodName.contains("ByWorkspace")
                                    || methodName.contains("ForWorkspace");

                            boolean hasWorkspaceParam = method.getRawParameterTypes().stream()
                                    .anyMatch(p -> p.getSimpleName().equals("Long")
                                            || // workspaceId
                                            p.getSimpleName().equals("Workspace"));

                            boolean hasQueryWithWorkspaceFilter = false;
                            if (method.isAnnotatedWith(Query.class)) {
                                Query q = method.getAnnotationOfType(Query.class);
                                hasQueryWithWorkspaceFilter = q.value().contains("workspaceId")
                                        || q.value().contains("workspace.id")
                                        || q.value().contains("JOIN Workspace");
                            }

                            if (!isWorkspaceScoped && !hasWorkspaceParam && !hasQueryWithWorkspaceFilter) {
                                events.add(SimpleConditionEvent.violated(
                                        method,
                                        String.format(
                                                "ENTITY RETURN RISK: %s.%s returns entity type but appears unscoped. "
                                                        + "Add workspace filtering or add to WORKSPACE_AGNOSTIC_METHODS if intentional.",
                                                repoName, methodName)));
                            }
                        }
                    };

            ArchRule rule = methods()
                    .that()
                    .areDeclaredInClassesThat()
                    .haveSimpleNameEndingWith("Repository")
                    .and()
                    .areDeclaredInClassesThat()
                    .areInterfaces()
                    .should(beWorkspaceScopedIfReturningEntity)
                    .because("Methods returning entities must be workspace-scoped");

            rule.check(classes);
        }
    }

    // CASCADE DELETE WORKSPACE SAFETY

    @Nested
    class CascadeDeleteSafetyTests {

        @Test
        void workspaceDoesNotCascadeDeleteGlobalEntities() {
            ArchCondition<JavaClass> notCascadeDeleteGlobalEntities =
                    new ArchCondition<>("not cascade delete global entities") {
                        @Override
                        public void check(JavaClass javaClass, ConditionEvents events) {
                            if (!javaClass.getSimpleName().equals("Workspace")) {
                                return;
                            }

                            javaClass.getFields().forEach(field -> {
                                String fieldType = field.getRawType().getSimpleName();

                                if (!GLOBAL_ENTITIES.contains(fieldType)) {
                                    return;
                                }

                                boolean hasCascadeDelete = field.getAnnotations().stream()
                                        .anyMatch(a -> {
                                            String annotationName =
                                                    a.getRawType().getSimpleName();
                                            if (!annotationName.equals("OneToMany")
                                                    && !annotationName.equals("OneToOne")
                                                    && !annotationName.equals("ManyToMany")) {
                                                return false;
                                            }
                                            try {
                                                Object cascade = a.getExplicitlyDeclaredProperty("cascade");
                                                if (cascade == null) return false;
                                                String cascadeStr = cascade.toString();
                                                return cascadeStr.contains("REMOVE") || cascadeStr.contains("ALL");
                                            } catch (Exception e) {
                                                return false;
                                            }
                                        });

                                if (hasCascadeDelete) {
                                    events.add(SimpleConditionEvent.violated(
                                            field,
                                            String.format(
                                                    "CASCADE DELETE RISK: Workspace.%s has cascade delete to global entity %s. "
                                                            + "This could delete shared data. Remove cascade or use orphanRemoval=false.",
                                                    field.getName(), fieldType)));
                                }
                            });
                        }
                    };

            ArchRule rule = classes()
                    .that()
                    .areAnnotatedWith(jakarta.persistence.Entity.class)
                    .should(notCascadeDeleteGlobalEntities)
                    .because("Workspace deletion should not cascade to global entities");

            rule.check(classes);
        }
    }
}
