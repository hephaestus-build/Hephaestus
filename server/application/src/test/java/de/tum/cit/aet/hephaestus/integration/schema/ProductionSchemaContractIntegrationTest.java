package de.tum.cit.aet.hephaestus.integration.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.audit.ConfigAuditRetentionJob;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditAction;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditActorKind;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountFeature;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountFeatureRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.core.runtime.ShedLockConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.slack.SlackConversationTestSupport;
import de.tum.cit.aet.hephaestus.integration.slack.conversation.SlackConversationProjector;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import de.tum.cit.aet.hephaestus.testconfig.TestCacheConfiguration;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Validates the production schema, its JPA mappings, and database-enforced contracts. */
@DataJpaTest(
        properties = {
            "spring.liquibase.enabled=true",
            "spring.liquibase.change-log=classpath:db/master.xml",
            "spring.liquibase.contexts=dev,prod",
            "spring.jpa.hibernate.ddl-auto=validate",
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    TestCacheConfiguration.class,
    SlackConversationProjector.class,
    ConfigAuditRetentionJob.class,
    ShedLockConfig.class,
    ProductionSchemaContractIntegrationTest.JsonConfiguration.class,
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ActiveProfiles("test")
@Tag("database")
class ProductionSchemaContractIntegrationTest {

    private static final String ACCOUNT_FEATURE = "run_practice_review";
    private static final String IDENTITY_SUBJECT = "583231";

    private static final TestDatabase DATABASE =
            PostgreSQLTestContainer.createDatabase("hephaestus_liquibase_validation");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::jdbcUrl);
        registry.add("spring.datasource.username", DATABASE::username);
        registry.add("spring.datasource.password", DATABASE::password);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LockProvider locks;

    @Autowired
    private SlackConversationProjector slackConversationProjector;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private ConfigAuditRetentionJob retentionJob;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private IdentityLinkRepository identityLinkRepository;

    @Autowired
    private AccountFeatureRepository accountFeatureRepository;

    @Autowired
    private IdentityProviderRepository identityProviderRepository;

    @Test
    void shouldAcquireAndReleaseSchedulerLocksAgainstTheMigratedSchema() {
        String name = "schema-" + UUID.randomUUID();
        var configuration = new LockConfiguration(Instant.now(), name, Duration.ofMinutes(1), Duration.ZERO);
        var first = locks.lock(configuration).orElseThrow();
        try {
            try {
                assertThat(locks.lock(configuration)).isEmpty();
            } finally {
                first.unlock();
            }
            var next = locks.lock(configuration).orElseThrow();
            next.unlock();
        } finally {
            jdbcTemplate.update("DELETE FROM shedlock WHERE name = ?", name);
        }
    }

    @Test
    void observationForeignKeysPreserveTenantAndProvenance() {
        List<@Nullable String> foreignKeys = jdbcTemplate.queryForList("""
            SELECT DISTINCT rc.constraint_name || ':' || parent.table_name || ':' || rc.delete_rule
            FROM information_schema.referential_constraints rc
            JOIN information_schema.key_column_usage child
              ON child.constraint_schema = rc.constraint_schema AND child.constraint_name = rc.constraint_name
            JOIN information_schema.constraint_column_usage parent
              ON parent.constraint_schema = rc.unique_constraint_schema
             AND parent.constraint_name = rc.unique_constraint_name
            WHERE rc.constraint_schema = current_schema()
              AND child.table_name = 'observation'
            """, String.class);
        assertThat(foreignKeys)
                .noneMatch(foreignKey -> foreignKey.endsWith(":CASCADE"))
                .contains(
                        "sfk_observation_workspace:workspace:NO ACTION",
                        "fk_observation_practice:practice:NO ACTION",
                        "fk_observation_practice_workspace:practice:NO ACTION",
                        "fk_observation_revision:practice_revision:SET NULL");

        assertColumnRequired("observation", "workspace_id");
    }

    @Test
    void accountFeatureIsScopedToTheProviderIdentity() {
        long providerA = provider("https://provider-a.example");
        long providerB = provider("https://provider-b.example");
        Account enabled = accountRepository.save(new Account("Provider A account"));
        Account disabled = accountRepository.save(new Account("Provider B account"));
        accountFeatureRepository.save(new AccountFeature(Objects.requireNonNull(enabled.getId()), ACCOUNT_FEATURE));
        link(enabled, providerA);
        link(disabled, providerB);

        assertThat(accountFeatureRepository.existsActiveFeatureForProviderSubject(
                        providerA, IDENTITY_SUBJECT, ACCOUNT_FEATURE))
                .isTrue();
        assertThat(accountFeatureRepository.existsActiveFeatureForProviderSubject(
                        providerB, IDENTITY_SUBJECT, ACCOUNT_FEATURE))
                .isFalse();
        assertThat(accountFeatureRepository.existsActiveFeatureForProviderSubject(
                        providerA, IDENTITY_SUBJECT, "mentor_access"))
                .isFalse();
        assertThat(accountFeatureRepository.existsActiveFeatureForProviderSubject(
                        999L, IDENTITY_SUBJECT, ACCOUNT_FEATURE))
                .isFalse();
    }

    @Test
    void disabledIdentityDoesNotCarryAccountFeature() {
        long provider = provider("https://disabled-provider.example");
        Account account = accountRepository.save(new Account("Disabled identity account"));
        accountFeatureRepository.save(new AccountFeature(Objects.requireNonNull(account.getId()), ACCOUNT_FEATURE));
        IdentityLink identity = link(account, provider);

        assertThat(accountFeatureRepository.existsActiveFeatureForProviderSubject(
                        provider, IDENTITY_SUBJECT, ACCOUNT_FEATURE))
                .isTrue();

        identity.setDisabledAt(java.time.Instant.now());
        identityLinkRepository.save(identity);

        assertThat(accountFeatureRepository.existsActiveFeatureForProviderSubject(
                        provider, IDENTITY_SUBJECT, ACCOUNT_FEATURE))
                .isFalse();
    }

    private IdentityLink link(Account account, long providerId) {
        IdentityLink identity = new IdentityLink();
        identity.setAccount(account);
        identity.setProviderId(providerId);
        identity.setSubject(IDENTITY_SUBJECT);
        identity.setUsernameAtSignup("octocat");
        return identityLinkRepository.save(identity);
    }

    private long provider(String serverUrl) {
        return Objects.requireNonNull(identityProviderRepository
                .save(new IdentityProvider(IdentityProviderType.GITHUB, serverUrl))
                .getId());
    }

    @Test
    @DisplayName("Production Liquibase schema applies cleanly and the JPA entities validate against it")
    void productionSchemaAppliesAndEntitiesValidate() {
        assertColumnExists("workspace", "account_login");
        assertColumnExists("connection", "credentials_encrypted");
        assertColumnExists("slack_message", "author_member_id");
        assertColumnExists("slack_thread", "participant_member_ids");
        assertColumnExists("slack_thread", "last_reviewed_ts");
        assertColumnExists("identity_provider", "type");
        assertIndexExists("idx_slack_thread_participants");
        assertIndexExists("idx_slack_message_ingest");
        assertConstraintExists("chk_feedback_dispatch_lease");
        assertConstraintExists("chk_feedback_dispatch_delivery");
        assertConstraintExists("chk_feedback_dispatch_suppression");
    }

    @Test
    void shouldRejectUnknownCoverageModes() {
        long workspaceId = insertWorkspace("coverage-mode-check");

        assertThatCode(() -> setCoverageMode(workspaceId, "practice_repository_coverage_mode", "SELECTED"))
                .as("a declared mode is what the entity writes")
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> setCoverageMode(workspaceId, "practice_repository_coverage_mode", "ALL"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_workspace_practice_repository_coverage");

        assertThatThrownBy(() -> setCoverageMode(workspaceId, "practice_person_coverage_mode", "EVERYONE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_workspace_practice_person_coverage");

        assertThatThrownBy(() -> setCoverageMode(workspaceId, "practice_delivery_status", "STOPPED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_workspace_practice_delivery_status");

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT practice_repository_coverage_mode FROM workspace WHERE id = ?",
                        String.class,
                        workspaceId))
                .as("a refused write leaves the last accepted mode in force")
                .isEqualTo("SELECTED");
    }

    @ParameterizedTest
    @MethodSource("invalidBaseBranches")
    void shouldRejectMalformedBaseBranches(String branches) {
        long workspaceId = insertWorkspace("base-branches-" + UUID.randomUUID());
        Long monitorId = jdbcTemplate.queryForObject(
                "INSERT INTO repository_to_monitor (workspace_id, name_with_owner) VALUES (?, ?) RETURNING id",
                Long.class,
                workspaceId,
                "acme/repository");
        assertThatCode(() -> jdbcTemplate.update(
                        "INSERT INTO practice_review_repository_target "
                                + "(workspace_id, repository_monitor_id, base_branches) VALUES (?, ?, '[]'::jsonb)",
                        workspaceId,
                        monitorId))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE practice_review_repository_target SET base_branches = CAST(? AS jsonb) "
                                + "WHERE workspace_id = ? AND repository_monitor_id = ?",
                        branches,
                        workspaceId,
                        monitorId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_practice_review_repository_target_branches");
    }

    @Test
    void repositoryCoverageCannotReferenceAnotherWorkspacesMonitor() {
        long ownerWorkspace = insertWorkspace("monitor-owner-" + UUID.randomUUID());
        long targetWorkspace = insertWorkspace("monitor-target-" + UUID.randomUUID());
        Long monitorId = jdbcTemplate.queryForObject(
                "INSERT INTO repository_to_monitor (workspace_id, name_with_owner) VALUES (?, ?) RETURNING id",
                Long.class,
                ownerWorkspace,
                "acme/repository");

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO practice_review_repository_target "
                                + "(workspace_id, repository_monitor_id, base_branches) VALUES (?, ?, '[]'::jsonb)",
                        targetWorkspace,
                        monitorId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("sfk_practice_review_repository_target_monitor");
    }

    @Test
    void peopleCoverageCannotReferenceAnotherWorkspacesMember() {
        long ownerWorkspace = insertWorkspace("member-owner-" + UUID.randomUUID());
        long targetWorkspace = insertWorkspace("member-target-" + UUID.randomUUID());
        Long providerId = jdbcTemplate.queryForObject(
                "INSERT INTO identity_provider (type, server_url) VALUES ('GITHUB', ?) RETURNING id",
                Long.class,
                "https://provider.example/" + UUID.randomUUID());
        Long userId = jdbcTemplate.queryForObject(
                "INSERT INTO \"user\" (native_id, provider_id, login, type) VALUES (1, ?, ?, 'USER') RETURNING id",
                Long.class,
                providerId,
                "member-" + UUID.randomUUID());
        jdbcTemplate.update(
                "INSERT INTO workspace_membership (workspace_id, user_id, role, league_points, hidden, created_at) "
                        + "VALUES (?, ?, 'MEMBER', 0, false, now())",
                ownerWorkspace,
                userId);

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO practice_review_person_target (workspace_id, user_id) VALUES (?, ?)",
                        targetWorkspace,
                        userId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("sfk_practice_review_person_target_membership");
    }

    @ParameterizedTest
    @MethodSource("invalidDispatchStates")
    void shouldRejectContradictoryDispatchState(String assignment, String constraint) {
        UUID dispatchId = insertDispatch("invalid-dispatch-" + UUID.randomUUID());

        assertThatThrownBy(() ->
                        jdbcTemplate.update("UPDATE feedback_dispatch SET " + assignment + " WHERE id = ?", dispatchId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> invalidDispatchStates() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("state = 'CLAIMED'", "chk_feedback_dispatch_lease"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "lease_owner = 'worker', lease_expires_at = now()", "chk_feedback_dispatch_lease"),
                org.junit.jupiter.params.provider.Arguments.of("state = 'SENT'", "chk_feedback_dispatch_delivery"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "state = 'SUPPRESSED'", "chk_feedback_dispatch_suppression"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "suppression_reason = 'WORKSPACE_DELIVERY_PAUSED'", "chk_feedback_dispatch_suppression"));
    }

    @Test
    void feedbackDispatchAcceptsCompleteTerminalStates() {
        UUID sent = insertDispatch("valid-sent");
        UUID suppressed = insertDispatch("valid-suppressed");

        assertThat(jdbcTemplate.update(
                        "UPDATE feedback_dispatch SET state = 'SENT', delivered_external_ref = 'provider-1' WHERE id = ?",
                        sent))
                .isEqualTo(1);
        assertThat(jdbcTemplate.update(
                        "UPDATE feedback_dispatch SET state = 'SUPPRESSED', suppression_reason = "
                                + "'WORKSPACE_DELIVERY_PAUSED' WHERE id = ?",
                        suppressed))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("invalidPracticeSlugs")
    void feedbackDispatchRequiresAStringArrayOfPracticeSlugs(String practiceSlugs) {
        UUID dispatchId = insertDispatch("invalid-practice-slugs-" + UUID.randomUUID());

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE feedback_dispatch SET practice_slugs = CAST(? AS jsonb) WHERE id = ?",
                        practiceSlugs,
                        dispatchId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void approvedDispatchRequiresFeedbackAndSummaryDispatchForbidsIt() {
        DispatchOwner owner = insertDispatchOwner("dispatch-destination");
        UUID feedbackId = insertFeedback(owner, "destination");
        UUID summary = insertDispatch(owner, "summary-destination", "AUTOMATIC_REVIEW_PACKAGE", null);

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE feedback_dispatch SET destination = 'APPROVED_REVIEW_PACKAGE' WHERE id = ?", summary))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE feedback_dispatch SET feedback_id = ? WHERE id = ?", feedbackId, summary))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID approved = insertDispatch(owner, "valid-approved", "APPROVED_REVIEW_PACKAGE", feedbackId);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT destination FROM feedback_dispatch WHERE id = ?", String.class, approved))
                .isEqualTo("APPROVED_REVIEW_PACKAGE");
    }

    @Test
    void deletingAJobErasesItsPolicyEvaluationsAndDispatches() {
        DispatchOwner owner = insertDispatchOwner("job-erasure");
        UUID dispatchId = insertDispatch(owner, "job-erasure-dispatch", "AUTOMATIC_REVIEW_PACKAGE", null);
        UUID evaluationId = insertPolicyEvaluation(owner, null);

        jdbcTemplate.update("DELETE FROM agent_job WHERE id = ?", owner.jobId());

        assertThat(rowExists("feedback_dispatch", dispatchId)).isFalse();
        assertThat(rowExists("delivery_policy_evaluation", evaluationId)).isFalse();
    }

    @Test
    void deletingFeedbackErasesApprovedDispatchAndAnonymizesItsEvaluation() {
        DispatchOwner owner = insertDispatchOwner("feedback-erasure");
        UUID feedbackId = insertFeedback(owner, "erasure");
        UUID dispatchId = insertDispatch(owner, "feedback-erasure-dispatch", "APPROVED_REVIEW_PACKAGE", feedbackId);
        UUID evaluationId = insertPolicyEvaluation(owner, feedbackId);

        jdbcTemplate.update("DELETE FROM feedback WHERE id = ?", feedbackId);

        assertThat(rowExists("feedback_dispatch", dispatchId)).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT feedback_id IS NULL FROM delivery_policy_evaluation WHERE id = ?",
                        Boolean.class,
                        evaluationId))
                .isTrue();
    }

    private UUID insertDispatch(String key) {
        DispatchOwner owner = insertDispatchOwner(key);
        return insertDispatch(owner, key, "AUTOMATIC_REVIEW_PACKAGE", null);
    }

    private DispatchOwner insertDispatchOwner(String key) {
        long workspaceId = insertWorkspace(key);
        UUID jobId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO agent_job (id, workspace_id, job_type, status, config_snapshot, job_token, trace_id, retry_count, "
                        + "created_at, available_at, delivery_attempts, practice_rollout_revision, practice_trigger_mode) "
                        + "VALUES (?, ?, 'PULL_REQUEST_REVIEW', 'COMPLETED', '{}'::jsonb, ?, ?, 0, now(), now(), 0, 0, 'AUTO')",
                jobId,
                workspaceId,
                "token-" + jobId,
                UUID.randomUUID().toString().replace("-", ""));
        return new DispatchOwner(workspaceId, jobId);
    }

    private UUID insertDispatch(DispatchOwner owner, String key, String destination, @Nullable UUID feedbackId) {
        UUID dispatchId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO feedback_dispatch (id, destination_key, workspace_id, agent_job_id, feedback_id, "
                        + "destination, state, body, practice_slugs, package_content, delivered_placements, write_started, "
                        + "next_attempt_at, attempt_count, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', "
                        + "'body', '[]'::jsonb, '{\"mrNote\":\"body\",\"diffNotes\":[],\"withheld\":[]}'::jsonb, "
                        + "'[]'::jsonb, false, now(), 0, now(), now())",
                dispatchId,
                key,
                owner.workspaceId(),
                owner.jobId(),
                feedbackId,
                destination);
        return dispatchId;
    }

    private UUID insertFeedback(DispatchOwner owner, String key) {
        Long providerId = jdbcTemplate.queryForObject(
                "INSERT INTO identity_provider (type, server_url) VALUES ('GITHUB', ?) RETURNING id",
                Long.class,
                "https://provider.example/" + key);
        Long userId = jdbcTemplate.queryForObject(
                "INSERT INTO \"user\" (native_id, provider_id, login, type) VALUES (1, ?, ?, 'USER') RETURNING id",
                Long.class,
                providerId,
                "user-" + key);
        UUID feedbackId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO feedback (id, agent_job_id, workspace_id, recipient_user_id, about_user_id, channel, "
                        + "position, delivery_state, source, created_at) VALUES (?, ?, ?, ?, ?, 'IN_CONTEXT', 0, "
                        + "'AWAITING_APPROVAL', 'AGENT', now())",
                feedbackId,
                owner.jobId(),
                owner.workspaceId(),
                userId,
                userId);
        return feedbackId;
    }

    private UUID insertPolicyEvaluation(DispatchOwner owner, @Nullable UUID feedbackId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO delivery_policy_evaluation (id, workspace_id, agent_job_id, feedback_id, "
                        + "admitted_revision, evaluated_revision, resolver_version, surface, stage, allowed, checks, facts, "
                        + "evaluated_at) VALUES (?, ?, ?, ?, 0, 0, '1', 'ARTIFACT', 'EGRESS', true, '[]'::jsonb, "
                        + "'{}'::jsonb, now())",
                id,
                owner.workspaceId(),
                owner.jobId(),
                feedbackId);
        return id;
    }

    private boolean rowExists(String table, UUID id) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM " + table + " WHERE id = ?)", Boolean.class, id));
    }

    static Stream<String> invalidPracticeSlugs() {
        return Stream.of("{}", "[1]", "[\"practice\", 2]");
    }

    private record DispatchOwner(long workspaceId, UUID jobId) {}

    static Stream<String> invalidBaseBranches() {
        return Stream.of("\"main\"", "[1]", "[\"\"]", "[\"   \"]", "[\"" + "x".repeat(256) + "\"]");
    }

    /** A workspace row with only the columns the schema demands; every setting below is left at its default. */
    private long insertWorkspace(String slug) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO workspace (slug, display_name, status, account_type, account_login, is_publicly_viewable) "
                        + "VALUES (?, ?, 'ACTIVE', 'ORG', ?, false) RETURNING id",
                Long.class,
                slug,
                "Review scope constraint fixture",
                slug);
        return Objects.requireNonNull(id, "workspace insert returned no id");
    }

    private void setCoverageMode(long workspaceId, String column, String mode) {
        jdbcTemplate.update("UPDATE workspace SET " + column + " = ? WHERE id = ?", mode, workspaceId);
    }

    private void assertIndexExists(String indexName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
                Integer.class,
                indexName);
        assertThat(count)
                .as("Liquibase-built schema must contain index %s", indexName)
                .isNotNull()
                .isEqualTo(1);
    }

    private void assertConstraintExists(String constraintName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_constraint WHERE conname = ?", Integer.class, constraintName);
        assertThat(count).isEqualTo(1);
    }

    private void assertColumnExists(String table, String column) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                Integer.class,
                table,
                column);
        assertThat(count)
                .as("Liquibase-built schema must contain column %s.%s", table, column)
                .isNotNull()
                .isEqualTo(1);
    }

    private void assertColumnRequired(String table, String column) {
        String nullable = jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                String.class,
                table,
                column);
        assertThat(nullable).isEqualTo("NO");
    }

    @Test
    void participantFirewallUsesTheMigratedArrayColumn() {
        long workspaceId = 900_101L;
        String channel = "C1";
        SlackConversationTestSupport support = new SlackConversationTestSupport(jdbcTemplate);
        jdbcTemplate.execute("SET session_replication_role = 'replica'");
        try {
            support.seedChannel(workspaceId, channel, "ACTIVE");
            support.seedThread(workspaceId, channel, "100.000000", "100.500000", 4, "{100,101}");
            support.seedMessage(workspaceId, channel, "100.000000", null, "root");
            support.seedMessage(workspaceId, channel, "100.500000", "100.000000", "reply");
            jdbcTemplate.update(
                    "UPDATE slack_message SET author_member_id = 100 WHERE workspace_id = ? AND slack_channel_id = ?",
                    workspaceId,
                    channel);
        } finally {
            jdbcTemplate.execute("SET session_replication_role = 'origin'");
        }

        ObjectNode participant = slackConversationProjector.buildPayload(workspaceId, 100L);
        ArrayNode conversations = (ArrayNode) participant.get("conversations");
        assertThat(conversations).hasSize(1);
        assertThat(conversations.get(0).get("channelName").asString()).isEqualTo("engineering");
        ArrayNode messages = (ArrayNode) conversations.get(0).get("messages");
        assertThat(messages).hasSize(2).allSatisfy(message -> {
            assertThat(message.get("text").asString()).isIn("root", "reply");
            assertThat(message.has("authorMemberId")).isFalse();
        });
        ArrayNode outsiderConversations = (ArrayNode)
                slackConversationProjector.buildPayload(workspaceId, 999L).get("conversations");
        assertThat(outsiderConversations).isEmpty();
    }

    @Test
    void configAuditRowsAreAppendOnlyWithTheRetentionException() {
        long workspaceId = auditWorkspaceId();
        long current = insertAuditRow(workspaceId, 0);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE config_audit_event SET entity_type = 'AGENT_CONFIG' WHERE id = ?", current))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM config_audit_event WHERE id = ?", current))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbcTemplate.execute("TRUNCATE config_audit_event"))
                .hasMessageContaining("append-only");

        assertThatCode(() -> jdbcTemplate.update(
                        "UPDATE config_audit_event SET actor_account_id = NULL WHERE id = ?", current))
                .doesNotThrowAnyException();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT new_value::text FROM config_audit_event WHERE id = ?", String.class, current))
                .contains("cooldownMinutes");
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE config_audit_event SET actor_account_id = NULL, entity_type = 'AGENT_CONFIG' WHERE id = ?",
                        current))
                .hasMessageContaining("append-only");

        long directlyExpired = insertAuditRow(workspaceId, ConfigAuditRetentionJob.RETENTION_DAYS + 1);
        assertThatCode(() -> jdbcTemplate.update("DELETE FROM config_audit_event WHERE id = ?", directlyExpired))
                .doesNotThrowAnyException();
        long stale = insertAuditRow(workspaceId, ConfigAuditRetentionJob.RETENTION_DAYS + 1);
        long inside = insertAuditRow(workspaceId, ConfigAuditRetentionJob.RETENTION_DAYS - 1);
        retentionJob.sweep();
        assertThat(auditRowExists(stale)).isFalse();
        assertThat(auditRowExists(inside)).isTrue();
        assertThat(auditRowExists(current)).isTrue();
    }

    static Stream<Arguments> auditEnumConstraints() {
        return Stream.of(
                Arguments.of("ck_config_audit_event_entity_type", ConfigAuditEntityType.values()),
                Arguments.of("ck_config_audit_event_action", ConfigAuditAction.values()),
                Arguments.of("ck_config_audit_event_actor_kind", ConfigAuditActorKind.values()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("auditEnumConstraints")
    void auditConstraintsAcceptEveryApplicationValue(String constraintName, Enum<?>[] values) {
        String definition = jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?", String.class, constraintName);
        List<String> accepted = Arrays.stream(Objects.requireNonNull(definition).split("'"))
                .filter(part -> part.matches("[A-Z_]{2,}"))
                .distinct()
                .toList();
        List<String> expected =
                new ArrayList<>(Arrays.stream(values).map(Enum::name).toList());
        if (constraintName.equals("ck_config_audit_event_entity_type")) {
            expected.addAll(List.of("PRACTICE_AREA", "CURATED_PRACTICE_AREA"));
        }
        assertThat(accepted).containsExactlyInAnyOrderElementsOf(expected);
    }

    private long auditWorkspaceId() {
        return workspaceRepository
                .findByWorkspaceSlug("audit-immutability")
                .orElseGet(() -> {
                    Workspace workspace = new Workspace();
                    workspace.setWorkspaceSlug("audit-immutability");
                    workspace.setDisplayName("Immutability");
                    workspace.setAccountLogin("audit-immutability-org");
                    workspace.setAccountType(AccountType.ORG);
                    return workspaceRepository.save(workspace);
                })
                .getId();
    }

    private long insertAuditRow(long workspaceId, int ageInDays) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject("""
                INSERT INTO config_audit_event
                    (workspace_id, entity_type, entity_id, action, actor_kind, actor_account_id,
                     old_value, new_value, changed_keys, occurred_at)
                VALUES (?, 'PRACTICE_REVIEW_SETTINGS', '1', 'UPDATED', 'USER', NULL,
                        '{"cooldownMinutes":30}'::jsonb, '{"cooldownMinutes":10}'::jsonb,
                        ARRAY['cooldownMinutes'], now() - make_interval(days => ?))
                RETURNING id
                """, Long.class, workspaceId, ageInDays));
    }

    private boolean auditRowExists(long id) {
        Integer count =
                jdbcTemplate.queryForObject("SELECT count(*) FROM config_audit_event WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    @TestConfiguration
    static class JsonConfiguration {

        @Bean
        tools.jackson.databind.ObjectMapper objectMapper() {
            return new tools.jackson.databind.ObjectMapper();
        }
    }
}
