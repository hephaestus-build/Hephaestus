package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.BASE_PACKAGE;
import static de.tum.cit.aet.hephaestus.architecture.conditions.HephaestusConditions.beNotNullableIfWorkspaceType;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import de.tum.cit.aet.hephaestus.architecture.conditions.WorkspaceQueryParameters;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionAudit;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.Entity;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** findById, findAllById and their fetch-join forms such as findAllByIdWithRelations; not findByIdModelId. */
    private static final Pattern BY_ID_LOAD = Pattern.compile("^find(?:All)?ById(?:In)?(?:With[A-Z][A-Za-z]*)?$");

    /**
     * Entity types that are workspace-scoped through their relationship chain.
     * Format: EntityName -> path to workspace (for documentation).
     */
    private static final Set<String> WORKSPACE_SCOPED_ENTITIES = Set.of(
            // Direct workspace relationship
            "ActivityEvent", // has Workspace field
            "RepositoryToMonitor", // has Workspace field
            "Membership", // has Workspace field
            // Through repository -> Workspace.organizationId (JOIN)
            "PullRequest",
            "Issue",
            "PullRequestReview",
            "PullRequestReviewComment",
            "PullRequestReviewThread",
            "IssueComment",
            "Label",
            "Milestone",
            // Through Workspace.organizationId (JOIN)
            "Repository",
            "Team",
            "TeamMembership",
            // Practice catalog entities
            "Practice", // has direct Workspace field
            "Result", // direct workspace_id scalar
            // Heph conversation ledger — every table carries a direct workspace_id scalar
            "MentorConversation",
            "MentorEntry",
            "MentorTurn",
            // Through Workspace.organizationId (ID-based relationship via JOIN)
            "OrganizationMembership", // organizationId -> Workspace.organizationId
            // Through project owner (ID-based: ownerId -> Organization/Repository/User)
            // Through repository (commit -> Repository -> Organization <- Workspace)
            "Commit", // through Repository -> Organization <- Workspace
            "CommitFileChange", // through Commit -> Repository -> Organization <- Workspace
            // Through repository (discussion -> Repository -> Organization <- Workspace)
            // Unified integration framework
            "Connection", // has direct Workspace field
            "ProviderEventRow", // workspace-owned events; installation events have no workspace yet
            "ConnectionAudit", // direct workspace_id scalar, equal to its connection's by foreign key
            // Product feedback and copies each carry the tenant independently.
            "Feedback", // direct workspace_id scalar
            "FeedbackCopy", // direct workspace_id scalar
            // Slack integration — every table carries a direct workspace_id scalar and is therefore
            // auto-classified scoped by WorkspaceScopedTables (absent from GLOBAL_TABLES). Listed here
            // so a future refactor that drops the column trips slackEntitiesCarryDirectWorkspaceId().
            "SlackMessage", // direct workspace_id scalar
            "SlackThread", // direct workspace_id scalar
            "SlackMonitoredChannel", // direct workspace_id scalar
            // Grant allowlist: workspace_id lives inside the composite @EmbeddedId, so the direct-field
            // probe below cannot see it. Scoped, not global — the row names the tenant it grants.
            "LlmModelWorkspaceGrant");

    @Test
    void shouldNameOnlyExistingClassesInWorkspaceScopedEntities() {
        AllowlistEntries.assertEachNamesAClass("WORKSPACE_SCOPED_ENTITIES", WORKSPACE_SCOPED_ENTITIES, classes);
    }

    // Package-private so WorkspaceScopedTablesParityTest can compare it with the production allowlist.
    static final Set<String> GLOBAL_ENTITIES = Set.of(
            "ProviderResourceRepair", // ADR 0101: security retention survives removal of every scoped owner
            "User", // Users can belong to multiple workspaces
            "CommitIdentityChange", // Numeric record of corrected links on shared commit rows
            "NotificationSubscription", // Native account email preferences span workspaces and are erased with that
            // account
            "Organization", // Synced from GitHub, workspace is set separately
            "Workspace", // Is the tenant root
            "WorkspaceSlugReservation", // Instance-wide DNS namespace tombstones
            "GitHubInstallationRequest", // Installations an instance admin decides before a workspace exists
            "WorkspaceSlugHistory", // Tracks workspace slug changes
            "IssueType", // GitHub issue types are workspace-scoped through issue
            "IdentityProvider", // Global provider instances (e.g., github.com, gitlab.com)
            "LlmConnection", // Instance-owned provider connection; global, not tenant-scoped
            "LlmModel", // Instance-curated model behind a connection; global
            "LlmModelPrice", // Instance model price history; global pricing authority
            "LlmCallPool", // Provider capacity shared by the workspaces whose models are in it
            "LlmCallPermit", // Admission metadata; WorkspaceTableColumnIntegrationTest says why it has a workspace_id
            "ProductFeedbackDestination", // Instance product feedback destination singleton
            "ProductFeedbackFiling", // A report's way to GitHub; owned by its product_feedback row, not a workspace
            "ProductFeedbackConsent", // A person's public-issue choices; account-owned, erased with the account
            "ProductFeedbackCleanup", // GitHub locators left after erasure; no person data
            "InstanceLlmSettings", // Instance LLM settings singleton (egress allowlist + BYO enable + default policy);
            // global
            "CatalogEntry",
            "FxRate", // ECB daily reference rate for display-only conversion; a world fact, not tenant data
            // core.auth (ADR 0017) — identity is user/system-scoped, not workspace-scoped.
            // Account ↔ Workspace association lives on Membership, not on these rows.
            "PersonDataRequest", // Instance-wide rights request, never a workspace endpoint
            "IdentityRestriction", // Exact provider identity restrictions span workspaces
            "Account", // Hephaestus-native principal; spans workspaces
            "Identity", // Federated-login association; user-scoped
            "AccountFeature", // Per-account feature opt-ins
            "AuthEvent", // Append-only auth audit; references workspace optionally, not scoped by it
            "PasskeyCredential", // Account-bound credential; spans workspaces
            "PasskeyChallenge", // Account and browser-session challenge; spans workspaces
            "PasskeyRecoveryCode", // Account recovery-code hash; spans workspaces
            "IssuedJwt", // JWT revocation list; account-scoped
            "ClientSession", // Installed-client session (refresh family); account-scoped like IssuedJwt
            "ClientSignInHandoff", // Single-use installed-client sign-in code; precedes any workspace choice
            "JwtSigningKey", // System-wide signing keys
            "SlackRequestClaim", // Slack request signatures, held 15 minutes, past the signature window
            "AccountExport", // GDPR Art. 20 self-service export; account-scoped, spans workspaces
            "LoginProvider", // Instance-scoped OAuth login provider (sign-in option); not workspace-scoped
            "ConsentDecision", // Account-scoped consent evidence; spans workspaces
            "ProviderEventStep", // Owned by its provider event and deleted with it
            // Fleet-wide public keys that verify attempt tokens; a token's workspace is a signed claim
            "AttemptPublicKey",
            "InstanceSettings", // Singleton instance-wide operator settings (silent-mode brake)
            "ReleaseStart" // The releases this instance started
            );

    @Test
    void shouldNameOnlyExistingClassesInGlobalEntities() {
        AllowlistEntries.assertEachNamesAClass("GLOBAL_ENTITIES", GLOBAL_ENTITIES, classes);
    }

    @Nested
    class EntityWorkspaceRelationshipTests {

        /*
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
                                                "ENTITY ISOLATION: %s is a JPA entity with no apparent workspace"
                                                        + " relationship. Add to WORKSPACE_SCOPED_ENTITIES with"
                                                        + " relationship path, or add to GLOBAL_ENTITIES if intentionally"
                                                        + " global.",
                                                entityName)));
                            }
                        }
                    };

            ArchRule rule = classes()
                    .that()
                    .areAnnotatedWith(Entity.class)
                    .and()
                    .resideInAPackage(BASE_PACKAGE + "..")
                    .should(haveWorkspacePath)
                    .because("All business entities must be traceable to a workspace for data isolation");

            rule.check(classes);
        }

        /**
         * Targeted tenancy proof for the Slack integration tables.
         *
         * <p>The {@code slack_*} entities are workspace-scoped by a direct
         * {@code workspace_id} scalar column (no FK chain); each must resolve to a real {@code workspaceId}
         * field so {@code WorkspaceScopedTables} classifies it scoped and the {@code StatementInspector}
         * rides a tenancy predicate on every query.
         */
        @Test
        void slackEntitiesCarryDirectWorkspaceId() {
            Set<String> slackEntities = Set.of("SlackMessage", "SlackThread", "SlackMonitoredChannel");
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
                                        "SLACK TENANCY: %s must carry a direct workspaceId field so it stays"
                                                + " workspace-scoped. Removing it silently un-scopes Slack PII across"
                                                + " tenants.",
                                        javaClass.getSimpleName())));
                    }
                }
            };

            ArchRule rule = classes()
                    .that()
                    .areAnnotatedWith(Entity.class)
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
                    .areAnnotatedWith(Entity.class)
                    // Installation lifecycle events arrive before a workspace exists.
                    .and()
                    .areDeclaredInClassesThat()
                    .doNotHaveSimpleName("ProviderEventRow")
                    .should(beNotNullableIfWorkspaceType())
                    .because("Workspace relationships should be required to prevent orphaned data");

            rule.check(classes);
        }
    }

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
                                                "DTO LEAK RISK: %s exposes %s directly. Use projection DTOs (e.g.,"
                                                        + " UserDTO, AuthorDTO) instead to prevent cross-workspace data"
                                                        + " leakage.",
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

    @Nested
    class RepositoryReturnTypeSafetyTests {

        @Test
        void entityReturningMethodsAreWorkspaceScoped() {
            entityReturningMethodsRule().check(classes);
        }

        @Test
        void shouldRejectAnUnscopedEntityReadByAnotherId() {
            var fixtures = new ClassFileImporter()
                    .importClasses(
                            UnscopedFixtureRepository.class,
                            ScopedFixtureRepository.class,
                            ExemptFixtureRepository.class);
            var violations = entityReturningMethodsRule()
                    .evaluate(fixtures)
                    .getFailureReport()
                    .getDetails();
            assertThat(violations)
                    .hasSize(6)
                    .anySatisfy(violation -> assertThat(violation).contains("UnscopedFixtureRepository.readHistory"))
                    .anySatisfy(
                            violation -> assertThat(violation).contains("UnscopedFixtureRepository.findByConnectionId"))
                    .anySatisfy(violation -> assertThat(violation).contains("UnscopedFixtureRepository.unbound"))
                    .anySatisfy(violation -> assertThat(violation).contains("UnscopedFixtureRepository.unused"))
                    .anySatisfy(violation -> assertThat(violation).contains("UnscopedFixtureRepository.readDefault"))
                    .anySatisfy(violation -> assertThat(violation)
                            .contains("UnscopedFixtureRepository.findByConnectionIdOrWorkspaceId"));
        }

        private ArchRule entityReturningMethodsRule() {
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
                            // A load by primary keys, with or without fetch joins: the caller already held each key.
                            if (standardMethods.contains(methodName)
                                    || BY_ID_LOAD.matcher(methodName).matches()) {
                                return;
                            }

                            if (method.isAnnotatedWith(WorkspaceAgnostic.class)) {
                                return;
                            }

                            if (method.getOwner().isAnnotatedWith(WorkspaceAgnostic.class)) {
                                return;
                            }

                            // The raw type of List<Foo> is List, so the entity is read from the type arguments too.
                            Set<String> returned = new HashSet<>();
                            collectTypeNames(method.getReturnType(), returned);
                            boolean returnsEntity = returned.stream().anyMatch(WORKSPACE_SCOPED_ENTITIES::contains);

                            if (!returnsEntity) {
                                return;
                            }

                            boolean isWorkspaceScoped;
                            if (method.isAnnotatedWith(Query.class)
                                    || method.reflect().isDefault()) {
                                isWorkspaceScoped = WorkspaceQueryParameters.bindsWorkspaceParameter(method.reflect());
                            } else {
                                isWorkspaceScoped = methodName.matches(".*By.*Workspace(?:Id|_Id)?(?:And|OrderBy|$).*")
                                        && !Pattern.compile("Or(?=[A-Z])")
                                                .matcher(methodName)
                                                .find();
                            }

                            if (!isWorkspaceScoped) {
                                events.add(SimpleConditionEvent.violated(
                                        method,
                                        String.format(
                                                "ENTITY RETURN RISK: %s.%s returns entity type but appears unscoped."
                                                        + " Add workspace filtering or mark it @WorkspaceAgnostic"
                                                        + " if intentional.",
                                                repoName, methodName)));
                            }
                        }
                    };

            return methods()
                    .that()
                    .areDeclaredInClassesThat()
                    .haveSimpleNameEndingWith("Repository")
                    .and()
                    .areDeclaredInClassesThat()
                    .areInterfaces()
                    .should(beWorkspaceScopedIfReturningEntity)
                    .because("Methods returning entities must be workspace-scoped");
        }
    }

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
                                                    "CASCADE DELETE RISK: Workspace.%s has cascade delete to global"
                                                            + " entity %s. This could delete shared data. Remove cascade"
                                                            + " or use orphanRemoval=false.",
                                                    field.getName(), fieldType)));
                                }
                            });
                        }
                    };

            ArchRule rule = classes()
                    .that()
                    .areAnnotatedWith(Entity.class)
                    .should(notCascadeDeleteGlobalEntities)
                    .because("Workspace deletion should not cascade to global entities");

            rule.check(classes);
        }
    }

    interface UnscopedFixtureRepository {
        List<ConnectionAudit> readHistory(long connectionId);

        List<ConnectionAudit> findByConnectionIdOrderByOccurredAtDesc(long workspaceId);

        @Query("SELECT a FROM ConnectionAudit a WHERE a.workspaceId = :workspaceId")
        List<ConnectionAudit> unbound(@Param("connectionId") long workspaceId);

        @Query("SELECT a FROM ConnectionAudit a WHERE a.connection.id = :connectionId")
        List<ConnectionAudit> unused(long workspaceId, long connectionId);

        default List<ConnectionAudit> readDefault(long connectionId) {
            return List.of();
        }

        List<ConnectionAudit> findByConnectionIdOrWorkspaceId(long connectionId, long workspaceId);
    }

    interface ScopedFixtureRepository {
        List<ConnectionAudit> findByWorkspaceIdAndConnectionId(long workspaceId, long connectionId);

        @Query("SELECT a FROM ConnectionAudit a WHERE a.workspaceId = :workspaceId")
        List<ConnectionAudit> readBoxed(@Param("workspaceId") Long wsId);

        @Query("SELECT a FROM ConnectionAudit a WHERE a.connection.workspace = :workspace")
        List<ConnectionAudit> readObject(Workspace workspace);

        default List<ConnectionAudit> readDefault(long workspaceId) {
            return findByWorkspaceIdAndConnectionId(workspaceId, 1L);
        }
    }

    interface ExemptFixtureRepository {
        @WorkspaceAgnostic("Explicit unscoped fixture for the architecture rule")
        List<ConnectionAudit> readHistory(long connectionId);
    }

    /** The simple names of a type and of every type argument in it, at any depth. */
    private static void collectTypeNames(JavaType type, Set<String> names) {
        names.add(type.toErasure().getSimpleName());
        if (type instanceof JavaParameterizedType parameterized) {
            for (JavaType argument : parameterized.getActualTypeArguments()) collectTypeNames(argument, names);
        }
    }
}
