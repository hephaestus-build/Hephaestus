package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.activity.ActivityFact;
import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.context.AttemptInputs;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.context.ReviewAttemptCustody;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobLifecycleService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorAdmission;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorRefusal;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnAdmission;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.oauth.state.OAuthStateNonceStore;
import de.tum.cit.aet.hephaestus.integration.core.oauth.state.OAuthStateService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessage;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThread;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryDraft;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryService;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerFixtures;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnClaims;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.ReviewOn;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopy;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.model.ResultMark;
import de.tum.cit.aet.hephaestus.practices.result.ResultMarkRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.PracticeModelTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceNamespaceReservation;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceSlugService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AccountErasureIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MentorTurnClaims claims;

    @Autowired
    private PracticeModelTestFixtures modelFixtures;

    @Autowired
    private WorkspaceNamespaceReservation namespaceReservations;

    @Autowired
    private WorkspaceSlugService workspaceSlugs;

    @TempDir
    private Path evidenceRoot;

    @Autowired
    private NamedParameterJdbcTemplate namedJdbc;

    @Autowired
    private PersonDataCopyFence copyFence;

    @Autowired
    private ObjectProvider<ReviewJobLifecycleService> lifecycles;

    @Autowired
    private PersonDataService personData;

    @Autowired
    private PracticeVersionService practiceVersions;

    @Autowired
    private PersonProcessingSuppression suppression;

    @Autowired
    private MentorAdmission mentor;

    @Autowired
    private ResultMarkRepository resultMarks;

    @Autowired
    private PersonDataRegistry registry;

    @Autowired
    private PersonIdentityResolver resolver;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OAuthStateNonceStore oauthNonces;

    @Autowired
    private IssuedJwtRepository issuedTokens;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private IdentityRepository links;

    @Autowired
    private UserRepository users;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private MembershipRepository memberships;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private SlackMessageRepository slackMessages;

    @Autowired
    private SlackThreadRepository slackThreads;

    @Autowired
    private MentorConversationService mentorConversations;

    @Autowired
    private MentorEntryService mentorEntries;

    @Autowired
    private MentorEntryRepository mentorEntryRepository;

    @Autowired
    private MentorTurnService mentorTurns;

    @Autowired
    private ReviewJobRepository agentJobRepository;

    @Autowired
    private ResultRepository practiceResultRepository;

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private FeedbackCopyRepository feedbackCopyRepository;

    @Autowired
    private LlmUsageRecorder usageRecorder;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private AttemptInputs attemptInputs;

    private static final Set<String> RETAINED_AFTER_ERASURE = Set.of(
            "account",
            "user",
            "identity_restriction",
            "person_data_request",
            "person_evidence_copy",
            "slack_thread",
            "outline_document_content",
            // The frozen selection names workspaces, not entries.
            MentorEntryRepository.COPIES_PERSON_DATA_STORE);

    @BeforeEach
    void clearRows() {
        databaseTestUtils.cleanDatabase();
    }

    @Test
    void shouldEraseAccountChoicesAndDerivedProofBeforeMembershipWhenTheAccountDeletesItself() {
        var provider = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://self-erasure.example.test"));
        var actor = users.saveAndFlush(TestUserFactory.createUser(42L, "self-erasure", provider));
        var account = accountFor(actor);
        long accountId = Objects.requireNonNull(account.getId());
        account.setAiChoice(AiChoice.NO_AI);
        account.setAiChoiceAt(Instant.now());
        accounts.saveAndFlush(account);
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("self-erasure"));
        var work = modelFixtures.work(workspace, "self-erasure-practice", "scm.pull_request", 42L);
        var result = modelFixtures.result(work, accountId, "Personal result");
        var feedback = modelFixtures.feedback(
                result, FeedbackDestination.PRACTICE_PAGE, FeedbackState.READY, "Personal feedback");
        personData.eraseForAccount(accountId);
        var tombstone = accounts.findById(accountId).orElseThrow();
        assertThat(tombstone.getStatus()).isEqualTo(Account.Status.DELETED);
        assertThat(tombstone.getAiChoice()).isNull();
        assertThat(tombstone.getAiChoiceAt()).isNull();
        assertThat(tombstone.isFeedbackDeliveryEnabled()).isFalse();
        assertThat(practiceResultRepository.findById(result.getId())).isEmpty();
        assertThat(feedbackRepository.findById(feedback.getId())).isEmpty();
        assertThat(memberships.findById_AccountId(accountId)).isEmpty();
        assertThat(links.findByAccount_Id(accountId)).isEmpty();
        assertThat(suppression.isAccountSuppressed(accountId)).isTrue();
        assertThat(suppression.isSuppressed(Objects.requireNonNull(provider.getId()), "42", null))
                .isTrue();
    }

    @Test
    void shouldEraseThePersonsPracticeAuthorshipRateLimitsAndPurgedNamespaceWhenAnAdministratorErasesThem() {
        Account administrator = new Account("Administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        long administratorId =
                Objects.requireNonNull(accounts.saveAndFlush(administrator).getId());
        Account target = accounts.saveAndFlush(new Account("Target"));
        long targetId = Objects.requireNonNull(target.getId());
        long otherId = Objects.requireNonNull(
                accounts.saveAndFlush(new Account("Other")).getId());
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("practice-author"));
        var github = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> providers.saveAndFlush(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        users.saveAndFlush(TestUserFactory.createUser(4242L, "target-login", github));
        link(target, github, "4242", null);
        long personalInstallation = installation("personal-install", "Target-Login", AccountType.USER);
        long activeInstallation = installation("active-personal", "target-login", AccountType.USER);
        jdbc.update("UPDATE workspace SET status='PURGED' WHERE slug='personal-install'");
        // The slug that the login names, which an earlier slug of the workspace was.
        jdbc.update("INSERT INTO workspace_slug_reservation(slug) VALUES ('target-login') ON CONFLICT DO NOTHING");
        // Neither an organization of the same name nor another person's installation is the person's.
        installation("org-install", "target-login", AccountType.ORG);
        installation("other-install", "other-login", AccountType.USER);
        long authored = authoredVersion(workspace, "target-authored", targetId);
        long otherAuthored = authoredVersion(workspace, "other-authored", otherId);
        List<String> targetBuckets = List.of("review-request:acct:" + targetId, "export:acct:" + targetId);
        List<String> keptBuckets = List.of(
                "review-request:acct:" + otherId,
                "review-request:acct:" + targetId + "0",
                "review-request:ip:192.0.2.7");
        for (String bucket :
                Stream.concat(targetBuckets.stream(), keptBuckets.stream()).toList())
            jdbc.update(
                    "INSERT INTO auth_rate_limit_bucket(id, state, expires_at) VALUES (?, ?, ?)",
                    bucket,
                    new byte[] {1},
                    Long.MAX_VALUE);

        var preview = personData.preview(administratorId, targetId, List.of());
        UUID requestId = preview.request().getId();
        var export = personData.export(requestId);
        assertThat(export.path("stores").path("practice_version"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.path("id").asLong()).isEqualTo(authored);
                    assertThat(row.path("created_by_account_id").asLong()).isEqualTo(targetId);
                });
        assertThat(export.path("stores").path("auth_rate_limit_bucket"))
                .extracting(row -> row.path("id").asString())
                .containsExactlyInAnyOrderElementsOf(targetBuckets);
        assertThat(export.path("stores").path("auth_rate_limit_bucket").toString())
                .doesNotContain("state");
        assertThat(export.path("stores").path("installation_account"))
                .extracting(row ->
                        row.path("id").asLong() + " " + row.path("display_name").asString())
                .containsExactlyInAnyOrder(
                        personalInstallation + " Target-Login", activeInstallation + " target-login");

        personData.requestErasure(requestId, administratorId, false);
        personData.run(requestId);

        assertThat(personData.get(requestId).request().getState()).isEqualTo(PersonDataRequest.State.COMPLETE);
        String author = "SELECT created_by_account_id FROM practice_version WHERE id=?";
        assertThat(jdbc.queryForObject(author, Long.class, authored)).isNull();
        assertThat(jdbc.queryForObject(author, Long.class, otherAuthored)).isEqualTo(otherId);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM practice p JOIN practice_version v ON v.id=p.current_version_id"
                                + " WHERE v.id=? AND v.definition IS NOT NULL",
                        Long.class,
                        authored))
                .as("the shared practice keeps its definition")
                .isOne();
        assertThat(jdbc.queryForList("SELECT id FROM auth_rate_limit_bucket", String.class))
                .doesNotContainAnyElementsOf(targetBuckets)
                .containsAll(keptBuckets);
        var erased = jdbc.queryForMap(
                "SELECT w.id, w.slug, w.display_name, w.account_login, c.display_name AS connection_name,"
                        + " (c.config->'orgLogin') IS NOT NULL AS has_login FROM connection c JOIN workspace w ON w.id=c.workspace_id"
                        + " WHERE c.id=?",
                personalInstallation);
        String erasedSlug = "erased-" + erased.get("id");
        assertThat(erased)
                .as("the purged personal workspace keeps no login")
                .containsEntry("slug", erasedSlug)
                .containsEntry("display_name", "Erased workspace")
                .containsEntry("connection_name", erasedSlug)
                .containsEntry("has_login", false);
        assertThat(erased.toString()).doesNotContainIgnoringCase("target-login");
        assertThat(erased).containsEntry("account_login", erasedSlug);
        assertThat(jdbc.queryForList("SELECT slug FROM workspace_slug_reservation", String.class))
                .doesNotContain("personal-install", "target-login")
                .contains(
                        namespaceReservations.slugToken("personal-install").orElseThrow(),
                        namespaceReservations.slugToken("target-login").orElseThrow(),
                        erasedSlug);
        assertThat(workspaceSlugs.isAvailable("personal-install"))
                .as("the erased slug stays reserved")
                .isFalse();
        assertThat(jdbc.queryForObject(
                        "SELECT display_name FROM connection WHERE id=?", String.class, activeInstallation))
                .as("an active personal workspace keeps its login as its namespace")
                .isEqualTo("target-login");
    }

    /** A workspace installed on a provider account with this login, and its GitHub connection. */
    private long installation(String slug, String login, AccountType type) {
        var workspace = WorkspaceTestFixtures.activeWorkspace(slug);
        workspace.setAccountLogin(login);
        workspace.setAccountType(type);
        workspace = workspaces.saveAndFlush(workspace);
        var connection = new Connection(
                workspace,
                IntegrationKind.GITHUB,
                slug,
                new ConnectionConfig.GitHubAppConfig(1L, login, null, Set.of()));
        connection.setDisplayName(login);
        return Objects.requireNonNull(connections.saveAndFlush(connection).getId());
    }

    private long authoredVersion(Workspace workspace, String slug, long accountId) {
        var kind = new ItemKind("scm.pull_request");
        var definition = DefinitionTestFixtures.withLineNotes(
                DefinitionTestFixtures.withReviewOn(
                        DefinitionTestFixtures.definition(kind, slug, "Check the authored practice.", null),
                        new ReviewOn(List.of(), Map.of())),
                false);
        var practice = practiceVersions.create(
                new PracticeVersionService.NewPractice(workspace, slug, kind, definition, null, 0, accountId));
        return Objects.requireNonNull(practice.getCurrentVersion().getId());
    }

    @Test
    void shouldEraseNotMetResultsAndTheirProblemsWithoutTouchingAnotherPersonsResult() {
        Account administrator = new Account("Administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        long administratorId =
                Objects.requireNonNull(accounts.saveAndFlush(administrator).getId());
        long targetId = Objects.requireNonNull(
                accounts.saveAndFlush(new Account("Target")).getId());
        long otherId = Objects.requireNonNull(
                accounts.saveAndFlush(new Account("Other")).getId());
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("privacy-not-met"));
        var targetWork = modelFixtures.work(workspace, "not-met-erased", "scm.pull_request", 42L);
        var targetResult =
                modelFixtures.resultWithProblem(targetWork, targetId, "Result title", "erasure-profile-canary");
        var otherWork = modelFixtures.work(workspace, "not-met-retained", "scm.pull_request", 84L);
        var otherResult =
                modelFixtures.resultWithProblem(otherWork, otherId, "Result title", "preserved-profile-canary");

        UUID requestId = personData
                .preview(administratorId, targetId, List.of())
                .request()
                .getId();
        personData.requestErasure(requestId, administratorId, false);
        personData.run(requestId);

        var receipt = personData.get(requestId).request();
        Long remainingResults =
                jdbc.queryForObject("SELECT count(*) FROM result WHERE id=?", Long.class, targetResult.getId());
        Long remainingProblems =
                jdbc.queryForObject("SELECT count(*) FROM problem WHERE result_id=?", Long.class, targetResult.getId());
        assertThat(receipt.getState())
                .as(
                        "erasure failure: %s; result rows=%s; problem rows=%s",
                        receipt.getFailureCode(), remainingResults, remainingProblems)
                .isEqualTo(PersonDataRequest.State.COMPLETE);
        assertThat(remainingResults).isZero();
        assertThat(remainingProblems).isZero();
        Map<String, Long> erased = PersonDataStoreReceipt.counts(mapper, receipt.getCompletedJson());
        assertThat(erased).containsEntry("result", 1L).containsEntry("problem", 1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM result WHERE id=?", Long.class, otherResult.getId()))
                .isOne();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM problem WHERE result_id=?", Long.class, otherResult.getId()))
                .isOne();
    }

    @Test
    void shouldRedactAnErasedAdministratorFromMarksAndDecisionsOnAnotherPersonsWorkAndKeepTheHistory() {
        Account operator = new Account("Operator");
        operator.setAppRole(Account.AppRole.APP_ADMIN);
        long operatorId = Objects.requireNonNull(accounts.saveAndFlush(operator).getId());
        long deciderId = Objects.requireNonNull(
                accounts.saveAndFlush(new Account("Decider")).getId());
        long developerId = Objects.requireNonNull(
                accounts.saveAndFlush(new Account("Developer")).getId());
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("decision-history"));
        var result = modelFixtures.result(
                modelFixtures.work(workspace, "decided-practice", "scm.pull_request", 71L),
                developerId,
                "Developer result");
        var feedback = modelFixtures.feedback(
                result, FeedbackDestination.PRACTICE_PAGE, FeedbackState.READY, "Developer feedback");
        UUID mark = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO result_mark(id, workspace_id, result_id, kind, reason, created_by_id, created_at,
                    removed_by_id, removed_at, removal_reason)
                VALUES (?, ?, ?, 'INCORRECT', 'Marked by the developer', ?, now() - interval '1 hour', ?, now(),
                    'Decider removal words')
                """, mark, result.getWorkspaceId(), result.getId(), developerId, deciderId);
        UUID decision = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO feedback_decision(id, workspace_id, feedback_id, action, actor_account_id, reason, note,
                    content_digest)
                VALUES (?, ?, ?, 'REJECT', ?, 'Decider reason words', 'Decider note words', ?)
                """, decision, result.getWorkspaceId(), feedback.getId(), deciderId, "a".repeat(64));

        var preview = personData.preview(operatorId, deciderId, List.of());
        UUID requestId = preview.request().getId();
        personData.requestErasure(requestId, operatorId, false);
        personData.run(requestId);

        assertThat(personData.get(requestId).request().getState()).isEqualTo(PersonDataRequest.State.COMPLETE);
        assertThat(jdbc.queryForMap(
                        "SELECT removed_by_id, removal_reason, removed_at IS NOT NULL AS removed, created_by_id"
                                + " FROM result_mark WHERE id=?",
                        mark))
                .containsEntry("removed_by_id", null)
                .containsEntry("removal_reason", "Erased administrator text")
                .containsEntry("removed", true)
                .containsEntry("created_by_id", developerId);
        assertThat(jdbc.queryForMap(
                        "SELECT actor_account_id, reason, note, action, content_digest FROM feedback_decision"
                                + " WHERE id=?",
                        decision))
                .containsEntry("actor_account_id", null)
                .containsEntry("reason", "Erased administrator text")
                .containsEntry("note", null)
                .containsEntry("action", "REJECT")
                .containsEntry("content_digest", "a".repeat(64));
        assertThatThrownBy(() -> jdbc.update("UPDATE feedback_decision SET action='WITHDRAW' WHERE id=?", decision))
                .as("a history row still refuses an edit that is not the privacy redaction")
                .hasMessageContaining("feedback history is append-only");
        assertThatThrownBy(() -> jdbc.update("UPDATE feedback_decision SET reason='Other words' WHERE id=?", decision))
                .hasMessageContaining("feedback history is append-only");
        assertThatThrownBy(() -> jdbc.update("UPDATE feedback_decision SET note='Other words' WHERE id=?", decision))
                .hasMessageContaining("feedback history is append-only");
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE feedback_decision SET note=NULL, decided_at=now() - interval '1 day' WHERE id=?",
                        decision))
                .as("a redaction cannot change another column")
                .hasMessageContaining("feedback history is append-only");
    }

    @Test
    void shouldPreserveUnconfirmedCopyCustodyUntilExternalRemovalIsConfirmed() {
        var fixture = externalInspectionFixture("https://privacy-gitlab.example.com/team/repo/-/merge_requests/19");
        jdbc.update("UPDATE feedback SET state='UNCONFIRMED' WHERE id=?", fixture.feedbackId());
        var preview = personData.preview(
                fixture.administratorId(), null, List.of(new PersonIdentity(fixture.providerId(), "42", null)));
        assertThat(preview.externalDeliveries())
                .contains(new PersonDataContributor.ExternalDelivery(
                        fixture.workspaceId(), "https://privacy-gitlab.example.com/team/repo/-/merge_requests/19"));
        assertThatThrownBy(() -> personData.requestErasure(preview.request().getId(), fixture.administratorId(), false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("un-deliver runbook");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM feedback_copy WHERE feedback_id=?", Long.class, fixture.feedbackId()))
                .isEqualTo(1L);
    }

    @Test
    void shouldRejectAnOldPreviewWhenAProviderCopyReceiptChanges() {
        var fixture = externalInspectionFixture("https://privacy-gitlab.example.com/team/repo/-/merge_requests/19");
        var preview = personData.preview(
                fixture.administratorId(), null, List.of(new PersonIdentity(fixture.providerId(), "42", null)));
        jdbc.update(
                "UPDATE feedback_copy SET"
                        + " state='POSTED',posted_at=CURRENT_TIMESTAMP,external_ref='note-99'"
                        + " WHERE feedback_id=?",
                fixture.feedbackId());
        assertThatThrownBy(() -> personData.export(preview.request().getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("preview scope changed");
        assertThatThrownBy(() -> personData.requestErasure(preview.request().getId(), fixture.administratorId(), true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("preview scope changed");
        assertThat(personData.get(preview.request().getId()).request().getState())
                .isEqualTo(PersonDataRequest.State.PREVIEW);
    }

    @Test
    void shouldSuppressSelectedJobsAndRevokeCredentialsWhenErasureFinishes() {
        var fixture = externalInspectionFixture("https://privacy-gitlab.example.com/team/repo/-/merge_requests/19");
        jdbc.update(
                "UPDATE feedback_copy SET state='POSTED',posted_at=CURRENT_TIMESTAMP,external_ref='note-99' WHERE feedback_id=?",
                fixture.feedbackId());
        var preview = personData.preview(
                fixture.administratorId(), null, List.of(new PersonIdentity(fixture.providerId(), "42", null)));
        personData.requestErasure(preview.request().getId(), fixture.administratorId(), true);
        assertThat(suppression.isReviewJobSuppressed(fixture.jobId())).isTrue();
        personData.run(preview.request().getId());
        assertThat(personData.get(preview.request().getId()).request().getState())
                .isEqualTo(PersonDataRequest.State.COMPLETE);
        assertThat(suppression.isReviewJobSuppressed(fixture.jobId())).isFalse();
        assertThat(agentJobRepository.findById(fixture.jobId())).isEmpty();
    }

    @Test
    void shouldDetachTheLedgerRowThatCancellingARunningSelectedJobSettlesWhenErasureRuns() throws Exception {
        var provider = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://running-usage.example.test"));
        var target = users.saveAndFlush(TestUserFactory.createUser(42L, "running-usage", provider));
        var administrator = new Account("Administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        administrator = accounts.saveAndFlush(administrator);
        long administratorId = Objects.requireNonNull(administrator.getId());
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("running-usage"));
        long workspaceId = Objects.requireNonNull(workspace.getId());
        var work = modelFixtures.work(workspace, "running-usage-" + workspaceId, "scm.pull_request", 42L);
        modelFixtures.result(work, Objects.requireNonNull(accountFor(target).getId()), "Running result");
        UUID jobId = work.job().getId();
        LlmPriceSnapshot price = new LlmPriceSnapshot(
                UsageFundingSource.INSTANCE,
                UsagePricingState.PRICED,
                11L,
                null,
                new BigDecimal("1.00"),
                new BigDecimal("2.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO);
        ConfigSnapshot snapshot = new ConfigSnapshot(
                ConfigSnapshot.SCHEMA_VERSION,
                new FrozenModel(
                        LlmApiProtocol.OPENAI_COMPLETIONS,
                        "https://api.openai.com/v1",
                        "gpt-5",
                        UsageFundingSource.INSTANCE,
                        1L,
                        2L,
                        workspaceId,
                        null,
                        null,
                        price,
                        false),
                null,
                null,
                600);
        // The fixture ends the job; this run is still executing on its worker when the erasure starts.
        RuntimeJobTestData.updateJobs(
                jdbc,
                "UPDATE review_job SET status='RUNNING', processing_status='NONE', completed_at=NULL,"
                        + " started_at=CURRENT_TIMESTAMP, execution_started_at=CURRENT_TIMESTAMP,"
                        + " config_snapshot=CAST(? AS jsonb) WHERE id=?",
                mapper.writeValueAsString(snapshot.toJson(mapper)),
                jobId);
        UUID runId = agentJobRepository.findById(jobId).orElseThrow().getRunId();

        // Another workspace's financial row, which the erasure must leave exactly as it is.
        var otherWorkspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("running-usage-other"));
        long otherWorkspaceId = Objects.requireNonNull(otherWorkspace.getId());
        var otherWork = modelFixtures.work(otherWorkspace, "running-usage-other", "scm.pull_request", 77L);
        UUID otherJobId = otherWork.job().getId();
        UUID otherRun = Objects.requireNonNull(otherWork.job().getRunId());
        UUID otherCall = openCall(
                otherWorkspaceId,
                otherRun,
                UsageSourceType.REVIEW_JOB,
                otherJobId,
                otherWork.review().getId(),
                price);
        completeCall(otherWorkspaceId, otherCall, 77, 7);
        String otherRowQuery = "SELECT * FROM llm_usage_event WHERE workspace_id=? AND source_id=?";
        var otherRow = jdbc.queryForMap(otherRowQuery, otherWorkspaceId, otherJobId);

        // One call of the running attempt ended with reported usage, and one more is still at its provider.
        UUID ended = openCall(
                workspaceId,
                runId,
                UsageSourceType.REVIEW_JOB,
                jobId,
                work.review().getId(),
                price);
        completeCall(workspaceId, ended, 1000, 100);
        UUID stillOut = openCall(
                workspaceId,
                runId,
                UsageSourceType.REVIEW_JOB,
                jobId,
                work.review().getId(),
                price);

        long providerId = Objects.requireNonNull(provider.getId());
        var preview = personData.preview(administratorId, null, List.of(new PersonIdentity(providerId, "42", null)));
        UUID requestId = preview.request().getId();
        personData.requestErasure(requestId, administratorId, false);
        personData.run(requestId);
        assertThat(personData.get(requestId).request().getState()).isEqualTo(PersonDataRequest.State.COMPLETE);

        // The cancellation settled the call still out; both rows keep their spend, not the job.
        String workspaceRows = "SELECT id, source_id, review_id, source_type, input_tokens, output_tokens,"
                + " total_calls, cost_usd, pricing_state, measurement_verification, funding_source FROM"
                + " llm_usage_event WHERE workspace_id=? ORDER BY input_tokens DESC";
        var settled = jdbc.queryForList(workspaceRows, workspaceId);
        assertThat(settled).hasSize(2);
        var measured = settled.getFirst();
        var unverifiable = settled.get(1);
        assertThat(measured.get("id")).isEqualTo(ended);
        assertThat(measured.get("source_id")).isNotNull().isNotEqualTo(jobId);
        assertThat(unverifiable.get("source_id"))
                .as("the calls of one attempt stay together")
                .isEqualTo(measured.get("source_id"));
        assertThat(measured.get("review_id")).isNull();
        assertThat(unverifiable.get("review_id")).isNull();
        assertThat(measured.get("source_type")).isEqualTo(UsageSourceType.REVIEW_JOB.name());
        assertThat(measured.get("input_tokens")).isEqualTo(1000L);
        assertThat(measured.get("output_tokens")).isEqualTo(100L);
        assertThat(measured.get("total_calls")).isEqualTo(1);
        assertThat(measured.get("cost_usd")).isEqualTo(new BigDecimal("0.001200"));
        assertThat(measured.get("pricing_state")).isEqualTo(UsagePricingState.PRICED.name());
        assertThat(measured.get("funding_source")).isEqualTo(UsageFundingSource.INSTANCE.name());
        assertThat(unverifiable.get("id")).isEqualTo(stillOut);
        assertThat(unverifiable.get("measurement_verification")).isEqualTo("UNVERIFIABLE");
        assertThat(unverifiable.get("pricing_state")).isEqualTo(UsagePricingState.UNPRICED.name());
        assertThat(unverifiable.get("cost_usd")).isNull();

        // The call still out returns only now: it measures its detached row and never names the job again.
        completeCall(workspaceId, stillOut, 500, 50);
        assertThat(jdbc.queryForMap(
                        "SELECT source_id, input_tokens, measurement_verification FROM llm_usage_event WHERE id=?",
                        stillOut))
                .containsEntry("source_id", measured.get("source_id"))
                .containsEntry("input_tokens", 500L)
                .containsEntry("measurement_verification", "VERIFIED");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM llm_usage_event WHERE source_id=? OR review_id=?",
                        Long.class,
                        jobId,
                        work.review().getId()))
                .isZero();
        assertThat(jdbc.queryForMap(otherRowQuery, otherWorkspaceId, otherJobId))
                .isEqualTo(otherRow);
    }

    /** Opens one call row under the run's current claim, as the proxy does before the call leaves. */
    private UUID openCall(
            long workspaceId,
            UUID runId,
            UsageSourceType sourceType,
            UUID sourceId,
            @Nullable UUID reviewId,
            LlmPriceSnapshot price) {
        int epoch = Objects.requireNonNull(
                jdbc.queryForObject("SELECT lease_epoch FROM agent_run WHERE id=?", Integer.class, runId));
        return Objects.requireNonNull(transactions.execute(tx -> usageRecorder.open(new LlmUsageRecorder.OpenCall(
                workspaceId,
                runId,
                Math.max(1, epoch),
                sourceType == UsageSourceType.MENTOR_TURN ? "MENTOR_TURN" : "PRACTICE_REVIEW",
                sourceType,
                sourceId,
                Math.max(0, sourceType == UsageSourceType.MENTOR_TURN ? epoch : epoch - 1),
                reviewId,
                ModelKind.CHAT,
                null,
                "gpt-5",
                price,
                null))));
    }

    private void completeCall(long workspaceId, UUID call, long input, long output) {
        transactions.executeWithoutResult(tx ->
                usageRecorder.complete(call, workspaceId, new LlmUsageRecorder.CallUsage(input, output, 0, 0, 0, 0)));
    }

    private void keepCompositionInputs(DerivedConversation derived) {
        jdbc.update("""
            INSERT INTO review_composition_basis(job_id,workspace_id,admission_attempt,admission_digest,
                basis_version,basis_digest,copy_id,expires_at,payload)
            VALUES (?,?,0,?,1,?,?,CURRENT_TIMESTAMP + INTERVAL '1 hour',
                '{"files":{"record":"ORIGINAL-BASIS-CANARY"}}'::jsonb)
            """, derived.jobId(), derived.workspaceId(), "a".repeat(64), "b".repeat(64), UUID.randomUUID());
    }

    private ExternalInspectionFixture externalInspectionFixture(@Nullable String reviewedWorkUrl) {
        var provider = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://privacy-gitlab.example.com"));
        var target = users.saveAndFlush(TestUserFactory.createUser(42L, "display-only", provider));
        var administrator = new Account("Administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        administrator = accounts.saveAndFlush(administrator);
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("external-inspection"));
        var work = modelFixtures.work(workspace, "external-inspection-" + workspace.getId(), "scm.pull_request", 42L);
        var result = modelFixtures.result(
                work, Objects.requireNonNull(accountFor(target).getId()), "Inspection result");
        var feedback =
                modelFixtures.feedback(result, FeedbackDestination.ON_WORK, FeedbackState.READY, "Prepared feedback");
        var job = work.job();
        jdbc.update("""
            INSERT INTO agent_action(id,workspace_id,kind,lane_key,idempotency_key,state,payload,subject_account_id,
              max_attempts,next_attempt_at,created_at,updated_at)
            SELECT uuidv7(),f.workspace_id,'DELIVER_FEEDBACK','feedback:' || f.id,'feedback:' || f.id || ':1:POST',
              'ADMITTED',jsonb_build_object('feedbackId',f.id),f.developer_id,8,CURRENT_TIMESTAMP + INTERVAL '1 day',
              CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
              FROM feedback f WHERE f.workspace_id=? AND f.id=?
            """, workspace.getId(), feedback.getId());
        var copyId = FeedbackIds.copy(feedback.getId(), FeedbackCopyKind.SUMMARY, 0);
        jdbc.update(
                """
                INSERT INTO feedback_copy(id,workspace_id,feedback_id,kind,position,state,body,write_key,write_marker,write_started_at,external_url,created_at)
                VALUES (?,?,?,'SUMMARY',0,'WRITE_STARTED',NULL,?,?,CURRENT_TIMESTAMP,?,CURRENT_TIMESTAMP)
                """,
                copyId,
                workspace.getId(),
                feedback.getId(),
                copyId.toString(),
                FeedbackIds.writeMarker(copyId),
                reviewedWorkUrl);
        return new ExternalInspectionFixture(
                Objects.requireNonNull(administrator.getId()),
                Objects.requireNonNull(provider.getId()),
                Objects.requireNonNull(workspace.getId()),
                job.getId(),
                feedback.getId());
    }

    private record ExternalInspectionFixture(
            long administratorId, long providerId, long workspaceId, UUID jobId, UUID feedbackId) {}

    @Test
    void erasesTargetAcrossTwoWorkspacesAndEveryRegisteredStoreWithoutTouchingAnotherPerson() throws Exception {
        IdentityProvider scm = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://privacy-gitlab.example.com"));
        IdentityProvider slack =
                providers.saveAndFlush(new IdentityProvider(IdentityProviderType.SLACK, "https://slack.com"));
        Account administrator = new Account("Administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        administrator = accounts.saveAndFlush(administrator);
        Account targetAccount = new Account("Target");
        targetAccount.setPrimaryEmail("person-all-stores-canary@example.test");
        targetAccount.setPrimaryEmailVerifiedAt(Instant.now());
        targetAccount.setTimeZone("Europe/Berlin");
        targetAccount.setAiChoice(AiChoice.CLOUD);
        targetAccount.setAiChoiceAt(Instant.now());
        targetAccount.setPublicActivityVisible(true);
        targetAccount.setPasskeyUserHandle(PersonErasureAllStoresFixture.CANARY);
        targetAccount.setPasskeyProtectionEnabled(true);
        targetAccount.setPasskeyRecoveryRequired(true);
        targetAccount = accounts.saveAndFlush(targetAccount);
        Account otherAccount = accounts.saveAndFlush(new Account("Other"));
        var targetOAuthBinding = new OAuthStateService.StateBinding(
                1L, IntegrationKind.GITHUB, Instant.now().truncatedTo(ChronoUnit.SECONDS), targetAccount.getId());
        oauthNonces.issue(
                "nonce-credential-canary",
                1L,
                IntegrationKind.GITHUB,
                targetOAuthBinding.issuedAt(),
                targetOAuthBinding.actorAccountId());
        UUID targetTokenId = UUID.randomUUID();
        var targetToken = new IssuedJwt(
                targetTokenId,
                Objects.requireNonNull(targetAccount.getId()),
                Instant.now(),
                Instant.now().plusSeconds(3600));
        issuedTokens.saveAndFlush(targetToken);
        UUID targetSession = UUID.randomUUID();
        UUID otherSession = UUID.randomUUID();
        for (var entry :
                Map.of(targetSession, targetAccount, otherSession, otherAccount).entrySet()) {
            jdbc.update("""
                INSERT INTO client_session(id,account_id,client_kind,client_id,session_expires_at,auth_time,created_at)
                VALUES (?,?,'BROWSER_EXTENSION','privacy-client',CURRENT_TIMESTAMP+INTERVAL '1 hour',
                    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, entry.getKey(), entry.getValue().getId());
            jdbc.update("""
                INSERT INTO client_sign_in_handoff(code_hash,account_id,client_kind,client_id,redirect_uri,
                    code_challenge,session_expires_at,auth_time,expires_at,created_at)
                VALUES (?,?,'BROWSER_EXTENSION','privacy-client','https://client.example.test/callback',
                    'pkce-credential-canary',CURRENT_TIMESTAMP+INTERVAL '1 hour',CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP+INTERVAL '60 seconds',CURRENT_TIMESTAMP)
                """, entry.getKey().toString(), entry.getValue().getId());
        }
        User target = users.saveAndFlush(TestUserFactory.createUser(42L, "target", scm));
        User other = users.saveAndFlush(TestUserFactory.createUser(84L, "other", scm));
        link(targetAccount, scm, "42", null);
        link(otherAccount, scm, "84", null);
        var recorder = new ExactPersonDataCopyRecorder();
        var worker = new EvidenceFolderPersonDataCatalog(
                new FabricLayout(evidenceRoot.toString()),
                jdbc,
                namedJdbc,
                mapper,
                recorder,
                copyFence,
                new DefaultListableBeanFactory().getBeanProvider(ReviewAttemptCustody.class),
                new DefaultListableBeanFactory().getBeanProvider(AgentSandboxes.class),
                new DefaultListableBeanFactory().getBeanProvider(SandboxGatewaySessions.class),
                lifecycles);
        List<Path> targetFolders = new ArrayList<>();
        List<Path> otherFolders = new ArrayList<>();
        List<DerivedConversation> targetDerived = new ArrayList<>();
        List<DerivedConversation> otherDerived = new ArrayList<>();
        List<Long> sharedThreads = new ArrayList<>();
        List<ConversationFeedbackCopy> copiedFeedback = new ArrayList<>();
        for (int index = 1; index <= 2; index++) {
            String team = "T" + index;
            Workspace workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("privacy-" + index));
            connections.saveAndFlush(new Connection(
                    workspace,
                    IntegrationKind.SLACK,
                    team,
                    new ConnectionConfig.SlackConfig(team, null, null, Set.of())));
            link(targetAccount, slack, "UTARGET", team);
            link(otherAccount, slack, "UOTHER", team);
            membership(workspace, target);
            membership(workspace, other);
            jdbc.update(
                    """
                    INSERT INTO config_audit_event(occurred_at,workspace_id,actor_kind,actor_account_id,
                        acting_account_id,entity_type,entity_id,action,changed_keys)
                    VALUES (CURRENT_TIMESTAMP,?,'IMPERSONATED',?,?,'WORKSPACE_VISIBILITY',?,'UPDATED',ARRAY['visibility'])
                    """,
                    workspace.getId(),
                    otherAccount.getId(),
                    targetAccount.getId(),
                    workspace.getId().toString());
            jdbc.update(
                    """
                    INSERT INTO config_audit_event(occurred_at,workspace_id,actor_kind,actor_account_id,
                        entity_type,entity_id,action,changed_keys,old_value,new_value)
                    VALUES (CURRENT_TIMESTAMP,?,'USER',?,'WORKSPACE_ROLE',?,'UPDATED',ARRAY['role'],
                        '{"role":"MEMBER","hidden":false}'::jsonb,'{"role":"ADMIN","hidden":false}'::jsonb)
                    """,
                    workspace.getId(),
                    administrator.getId(),
                    Objects.requireNonNull(targetAccount.getId()).toString());
            SlackThread shared = slackThread(workspace, "100.1", target, other);
            sharedThreads.add(shared.getId());
            message(workspace, team, "100.1", "100.1", "UTARGET", target, "Target's collected work");
            message(workspace, team, "100.1", "200.1", "UOTHER", other, "Other person's shared reply");
            SlackThread unrelated = slackThread(workspace, "300.1", other);
            message(workspace, team, "300.1", "300.1", "UOTHER", other, "Unrelated conversation");
            targetDerived.add(seedDerivedConversation(workspace, shared.getId(), target, index == 1));
            otherDerived.add(seedDerivedConversation(workspace, unrelated.getId(), other));
            copiedFeedback.add(seedFeedbackCopy(targetDerived.getLast(), otherDerived.getLast()));
            targetFolders.add(captureMountedEvidence(worker, recorder, targetDerived.getLast(), target));
            otherFolders.add(captureMountedEvidence(worker, recorder, otherDerived.getLast(), other));
        }
        Stream.concat(targetDerived.stream(), otherDerived.stream()).forEach(this::keepCompositionInputs);
        for (var derived : targetDerived) {
            var result = practiceResultRepository
                    .findById(derived.practiceResultIds().getFirst())
                    .orElseThrow();
            resultMarks.saveAndFlush(ResultMark.builder()
                    .id(UUID.randomUUID())
                    .workspaceId(result.getWorkspaceId())
                    .resultId(result.getId())
                    .createdById(Objects.requireNonNull(administrator.getId()))
                    .reason("Correction before access request")
                    .createdAt(Instant.now())
                    .build());
        }
        var allStores = new PersonErasureAllStoresFixture(jdbc);
        var firstDerived = targetDerived.getFirst();
        long firstWorkspace = firstDerived.workspaceId();
        var firstResult = practiceResultRepository
                .findById(firstDerived.practiceResultIds().getFirst())
                .orElseThrow();
        var onWork = modelFixtures.feedback(
                firstResult,
                FeedbackDestination.ON_WORK,
                FeedbackState.READY,
                "Practice guidance for developer " + target.getId());
        var subject = new PersonErasureAllStoresFixture.Subject(
                Objects.requireNonNull(targetAccount.getId()),
                target.getId(),
                Objects.requireNonNull(scm.getId()),
                firstWorkspace,
                firstResult.getReviewId(),
                firstDerived.jobId(),
                firstResult.getId(),
                onWork.getId());
        allStores.seed(subject);
        allStores.seedScm(target.getId(), Objects.requireNonNull(scm.getId()), 997000L);
        var github = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> providers.saveAndFlush(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        var githubUser = users.saveAndFlush(TestUserFactory.createUser(4242L, "target-github", github));
        link(targetAccount, github, "4242", null);
        allStores.seedScm(githubUser.getId(), Objects.requireNonNull(github.getId()), 998000L);
        allStores.seedProviderEvent(firstWorkspace, 4242L, PersonErasureAllStoresFixture.CANARY);
        installation("all-stores-personal", "target-github", AccountType.USER);
        // GitHub App installations: the target's own that no admin adopted, the target's adopted one, a decision
        // that the target made as an admin, and another person's own.
        String installationRow = """
                INSERT INTO github_installation_request
                  (installation_id, account_id, account_login, account_kind, status, received_at, decided_by_account_id)
                VALUES (?, ?, ?, ?, ?, now(), ?)
                """;
        jdbc.update(installationRow, 991001L, 4242L, "target-github", "USER", "PENDING", null);
        jdbc.update(installationRow, 991002L, 4242L, "target-github", "USER", "ADOPTED", null);
        jdbc.update(installationRow, 991003L, 7777L, "decided-org", "ORGANIZATION", "DECLINED", targetAccount.getId());
        jdbc.update(installationRow, 991004L, 8484L, "other-github", "USER", "PENDING", null);
        var outline = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.OUTLINE, "https://privacy-outline.example.test"));
        String outlineSubject = "00000000-0000-4000-8000-000000000042";
        link(targetAccount, outline, outlineSubject, null);
        var outlineConnection = connections.saveAndFlush(new Connection(
                workspaces.findById(firstWorkspace).orElseThrow(),
                IntegrationKind.OUTLINE,
                "",
                new ConnectionConfig.OutlineConfig(outline.getServerUrl(), null, null, Set.of())));
        allStores.seedOutline(firstWorkspace, Objects.requireNonNull(outlineConnection.getId()), outlineSubject);
        var otherSourceRows = new PersonErasureAllStoresFixture(jdbc, 995100L);
        otherSourceRows.seedScm(other.getId(), Objects.requireNonNull(scm.getId()), 997100L);
        otherSourceRows.seedActivityPublication(firstWorkspace, other.getId(), "other-person-activity");
        var otherGitHub = users.saveAndFlush(TestUserFactory.createUser(8484L, "other-github", github));
        link(otherAccount, github, "8484", null);
        otherSourceRows.seedScm(otherGitHub.getId(), Objects.requireNonNull(github.getId()), 998100L);
        otherSourceRows.seedProviderEvent(firstWorkspace, 8484L, "other-github");
        String otherOutlineSubject = "00000000-0000-4000-8000-000000000084";
        link(otherAccount, outline, otherOutlineSubject, null);
        otherSourceRows.seedOutline(
                firstWorkspace, Objects.requireNonNull(outlineConnection.getId()), otherOutlineSubject, 999100L);
        var preservedSourceRows = otherSourceRows.snapshot();
        authoredVersion(workspaces.findById(firstWorkspace).orElseThrow(), "all-stores-authored", subject.account());
        var ownConversation = mentorConversations.findOrCreateMain(firstWorkspace, subject.account());
        mentorTurns.submit(
                firstWorkspace,
                ownConversation.getId(),
                new MentorTurnService.Question(
                        MentorTurn.Surface.APP,
                        "Retained question",
                        UUID.randomUUID().toString(),
                        null,
                        null));
        var ownedTurn = claims.claimNext().orElseThrow();
        var model = LlmCatalogTestFixtures.admittedMentorConfig();
        transactions.executeWithoutResult(tx -> mentorTurns.admit(
                firstWorkspace,
                ownedTurn.getId(),
                ownedTurn.getAttempt(),
                Objects.requireNonNull(ownedTurn.getRunId()),
                MentorTurnAdmission.of(model, Objects.requireNonNull(model.priceSnapshot()))
                        .toJson()));

        // A pending email to each person: the outbox holds it until it is sent or erased.
        for (Account addressed : List.of(targetAccount, otherAccount)) {
            String key = "email:erasure-test:" + UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO agent_action (id, kind, lane_key, idempotency_key, state, payload, subject_account_id,
                      max_attempts, next_attempt_at, created_at, updated_at)
                    VALUES (?, 'EMAIL', ?, ?, 'ADMITTED', '{}', ?, 1, now() + interval '1 day', now(), now())
                    """, UUID.randomUUID(), key, key, addressed.getId());
        }
        PersonScope otherScope = resolver.resolve(Objects.requireNonNull(otherAccount.getId()), List.of());
        // Shared source aggregates change; compare the other person's own rows.
        PersonScope otherPrimaryScope =
                new PersonScope(otherScope.accountId(), otherScope.identities(), otherScope.userIds());
        var otherSelection = registry.select(otherPrimaryScope);
        Map<String, Object> otherRows = new TreeMap<>();
        for (var contributor : registry.stores())
            otherRows.put(
                    contributor.store(),
                    contributor.export(
                            Objects.requireNonNull(otherSelection.get(contributor.store())), otherPrimaryScope));
        long administratorId = Objects.requireNonNull(administrator.getId());
        var preview = personData.preview(administratorId, targetAccount.getId(), List.of());
        UUID requestId = preview.request().getId();
        PersonScope frozen =
                mapper.readValue(Objects.requireNonNull(preview.request().getScopeJson()), PersonScope.class);
        var export = personData.export(requestId);
        Map<String, Long> counts = mapper.readValue(preview.request().getCountsJson(), new TypeReference<>() {});
        Set<String> nonPersonalProjections = Set.of(
                "organization",
                "team",
                "team_repository_permission",
                "repository",
                "outline_collection",
                "slack_monitored_channel",
                "workspace_onboarding_settings");
        assertThat(counts.entrySet().stream()
                        .filter(entry -> !nonPersonalProjections.contains(entry.getKey()) && entry.getValue() == 0)
                        .map(Map.Entry::getKey)
                        .toList())
                .as("personal stores without a subject fixture")
                .isEmpty();
        assertThat(export.path("stores").propertyNames()).containsExactlyInAnyOrderElementsOf(counts.keySet());
        assertThat(counts.get("oauth_state_nonce")).isEqualTo(1L);
        assertThat(counts.get("client_session")).isEqualTo(1L);
        assertThat(counts.get("client_sign_in_handoff")).isEqualTo(1L);
        assertThat(export.path("stores").path("client_sign_in_handoff").toString())
                .doesNotContain(targetSession.toString(), "code_hash", "code_challenge");

        assertThat(counts.get("person_evidence_copy")).isEqualTo(2L);
        assertThat(counts.get("activity_publication")).isEqualTo(1L);
        assertThat(export.path("stores").path("review_job"))
                .extracting(row -> row.path("id").asString())
                .containsExactlyInAnyOrderElementsOf(targetDerived.stream()
                        .flatMap(derived -> practiceResultRepository.findAllById(derived.practiceResultIds()).stream())
                        .flatMap(result -> agentJobRepository
                                .findByReviewIdAndWorkspaceIdOrderByAvailableAtAsc(
                                        result.getReviewId(), result.getWorkspaceId())
                                .stream())
                        .map(job -> job.getId().toString())
                        .toList());
        for (var row : export.path("stores").path("review_job")) {
            assertThat(row.has("capture")).isFalse();
        }
        assertThat(export.toString())
                .doesNotContain("MOUNTED-PROFILE-CANARY", "credential-canary", "unrelated-profile-canary");
        targetFolders.forEach(path -> assertThat(path).exists());
        otherFolders.forEach(path -> assertThat(path).exists());
        assertThat(counts.get("feedback")).isEqualTo(5L);
        assertThat(preview.externalDeliveries()).isEmpty();
        // Each delivered feedback has its own conversation copy and the copy in another person's conversation.
        assertThat(export.path("stores").path("feedback_copy"))
                .extracting(row -> row.path("feedback_id").asString())
                .containsExactlyInAnyOrderElementsOf(targetDerived.stream()
                        .flatMap(derived -> Stream.of(derived.deliveredId(), derived.deliveredId()))
                        .map(UUID::toString)
                        .toList());
        assertThat(counts.get("result")).isEqualTo(4L);
        assertThat(export.path("stores").path("result"))
                .allSatisfy(row -> assertThat(row.path("summary").asString()).isEqualTo("Result title"));
        assertThat(export.path("stores").path("feedback"))
                .allSatisfy(row -> assertThat(row.path("body").toString())
                        .contains("Practice guidance for developer " + target.getId()));
        assertThat(counts.get("result_mark")).isEqualTo(3L);
        assertThat(export.path("stores").path("result_mark"))
                .allSatisfy(row -> assertThat(row.path("reason").asString())
                        .isIn("Correction before access request", PersonErasureAllStoresFixture.CANARY));
        assertThat(counts.get("config_audit_event_membership_subject")).isEqualTo(3L);
        assertThat(counts.get("mentor_conversation")).isEqualTo(2L);
        assertThat(counts.get("mentor_entry")).isEqualTo(3L);
        assertThat(counts.get(MentorEntryRepository.COPIES_PERSON_DATA_STORE)).isEqualTo(2L);
        assertThat(counts.get("mentor_entry_feedback_copy")).isEqualTo(2L);
        assertThat(export.path("stores").path("mentor_entry_feedback_copy").toString())
                .contains("Target guidance copied into another conversation")
                .doesNotContain("Other reply to erased feedback", "account_id", "credential-canary");
        assertThat(counts.get("slack_message")).isEqualTo(2L);
        counts.forEach((store, count) -> assertThat(
                        (long) export.path("stores").path(store).size())
                .as("Frozen preview/export parity for %s", store)
                // Tool results in another member's conversation are redacted, never exported.
                .isEqualTo(store.equals(MentorEntryRepository.COPIES_PERSON_DATA_STORE) ? 0L : count));
        assertThat(export.path("stores").path("mentor_entry"))
                .allSatisfy(
                        row -> assertThat(row.path("text").asString()).isIn("Delivered guidance", "Retained question"));
        assertThat(export.path("stores").path("mentor_entry").toString())
                .doesNotContain("payload", "runtime-credential-canary");
        assertThat(export.toString())
                .doesNotContain(
                        "Unrelated conversation",
                        "Other person's shared reply",
                        "credential-canary",
                        "unrelated-profile-canary");
        personData.requestErasure(requestId, administratorId, false);
        assertThat(oauthNonces.tryConsume("nonce-credential-canary", targetOAuthBinding))
                .isFalse();
        assertThat(issuedTokens.findActive(targetTokenId, Instant.now())).isEmpty();
        assertThat(issuedTokens.findById(targetTokenId).orElseThrow().getRevokedReason())
                .isEqualTo(IssuedJwt.RevokedReason.ACCOUNT_DELETED);
        assertThat(suppression.isSuppressed(
                        Objects.requireNonNull(target.getProvider().getId()),
                        target.getNativeId().toString(),
                        null))
                .isTrue();
        assertThat(suppression.isSuppressed(
                        Objects.requireNonNull(other.getProvider().getId()),
                        other.getNativeId().toString(),
                        null))
                .isFalse();
        for (var derived : targetDerived) {
            assertThat(mentor.refusal(derived.workspaceId(), targetAccount.getId()))
                    .contains(MentorRefusal.PERSON_ERASED);
            assertThat(mentor.refusal(derived.workspaceId(), otherAccount.getId()))
                    .isNotEqualTo(Optional.of(MentorRefusal.PERSON_ERASED));
        }
        for (long threadId : sharedThreads) {
            long workspaceId = Objects.requireNonNull(
                    jdbc.queryForObject("SELECT workspace_id FROM slack_thread WHERE id=?", Long.class, threadId));
            assertThat(suppression.isSourceSuppressed(workspaceId, "chat.thread", threadId))
                    .isTrue();
            assertThat(suppression.isSourceSuppressed(-1L, "chat.thread", threadId))
                    .isFalse();
        }
        // The erasure waits for no server; the server that holds the folders removes them in its next pass.
        personData.run(requestId);
        worker.removeErasedFolders();
        targetFolders.forEach(path -> assertThat(path).doesNotExist());
        otherFolders.forEach(path -> assertThat(path).exists());
        for (var derived : targetDerived) {
            assertThat(agentJobRepository.findById(derived.jobId())).isEmpty();
        }

        allStores.assertNoPersonalContentOrReferences(subject, mapper);
        assertThat(jdbc.queryForList(
                        "SELECT serialized_event FROM event_publication WHERE event_type = ?",
                        String.class,
                        ActivityFact.class.getName()))
                .as("queued activity facts: the person's are deleted, the other person's stay")
                .singleElement()
                .asString()
                .contains("\"actorId\":" + other.getId())
                .doesNotContain(PersonErasureAllStoresFixture.CANARY);
        assertThat(otherSourceRows.snapshot())
                .as("another person's GitHub, GitLab and Outline source rows")
                .isEqualTo(preservedSourceRows);
        var receipt = personData.get(requestId).request();
        assertThat(receipt.getState()).isEqualTo(PersonDataRequest.State.COMPLETE);
        assertThat(receipt.getScopeJson()).isNull();
        assertThat(receipt.getSelectionsJson()).isNull();
        Map<String, Long> completed = PersonDataStoreReceipt.counts(mapper, receipt.getCompletedJson());
        Map<String, Long> expectedReceipts = new TreeMap<>(counts);
        // Retained controls report no removal. Earlier owner steps already cleared the decision and resolver, and
        // the copies' erasure already deleted the recorded attempt inputs.
        for (String store : List.of(
                "identity_restriction",
                "git_repository_history",
                "installation_account",
                "feedback_decision_actor",
                "product_feedback_resolver",
                "review_attempt_input")) {
            expectedReceipts.put(store, 0L);
        }
        assertThat(completed)
                .as("store receipts, including the explicit retained/cascade exceptions")
                .isEqualTo(expectedReceipts);
        // Both closures may retain only anonymised, tombstone or control rows.
        var resolvedAfter = resolver.resolve(targetAccount.getId(), frozen.identities());
        Map<String, Long> reselected = registry.counts(registry.select(resolvedAfter));
        var frozenAfter = registry.select(frozen);
        Map<String, List<Long>> remaining = new TreeMap<>();
        for (var store : registry.stores()) {
            long resolvedRows = Objects.requireNonNull(reselected.get(store.store()));
            long frozenRows = Objects.requireNonNull(frozenAfter.get(store.store()))
                    .rows()
                    .size();
            if (resolvedRows != 0 || frozenRows != 0) remaining.put(store.store(), List.of(resolvedRows, frozenRows));
        }
        assertThat(remaining.keySet())
                .as("Rows still selected after erasure [re-resolved, frozen]: %s", remaining)
                .isSubsetOf(RETAINED_AFTER_ERASURE);
        assertThat(accounts.findById(Objects.requireNonNull(targetAccount.getId()))
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(Account.Status.DELETED);
        assertThat(jdbc.queryForList(
                        "SELECT installation_id FROM github_installation_request WHERE installation_id BETWEEN 991001 AND 991004"
                                + " ORDER BY installation_id",
                        Long.class))
                .as("the target's unadopted installation goes; the adopted one and another person's stay")
                .containsExactly(991002L, 991003L, 991004L);
        assertThat(jdbc.queryForObject(
                        "SELECT decided_by_account_id IS NULL AND status = 'DECLINED' FROM github_installation_request"
                                + " WHERE installation_id = 991003",
                        Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT display_name='deleted-account' AND primary_email IS NULL AND primary_email_verified_at IS NULL
                  AND time_zone IS NULL AND ai_choice IS NULL AND ai_choice_at IS NULL
                  AND NOT feedback_delivery_enabled AND NOT public_activity_visible
                  AND passkey_user_handle IS NULL AND NOT passkey_protection_enabled AND NOT passkey_recovery_required
                FROM account WHERE id=?
                """, Boolean.class, targetAccount.getId()))
                .isTrue();
        for (long userId : List.of(target.getId(), githubUser.getId())) {
            assertThat(jdbc.queryForObject("""
                    SELECT login='erased-'||id::text AND name IS NULL AND email IS NULL AND avatar_url='' AND html_url=''
                    FROM "user" WHERE id=?
                    """, Boolean.class, userId)).isTrue();
        }
        for (var derived : targetDerived) {
            assertThat(jdbc.queryForList(
                            "SELECT payload::text FROM person_evidence_copy WHERE job_id=? AND workspace_id=?",
                            String.class,
                            derived.jobId(),
                            derived.workspaceId()))
                    .allSatisfy(payload -> assertThat(payload).isEqualTo("{}"));
        }
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM identity_restriction WHERE active_request_id=?", Long.class, requestId))
                .isZero();
        for (var identity : frozen.identities()) {
            assertThat(suppression.isSuppressed(identity.providerId(), identity.subject(), identity.teamId()))
                    .as("suppression remains for each native identity")
                    .isTrue();
        }
        for (var derived : targetDerived) {
            assertThat(mentor.refusal(derived.workspaceId(), targetAccount.getId()))
                    .contains(MentorRefusal.PERSON_ERASED);
        }
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM config_audit_event WHERE actor_account_id=?"
                                + " AND acting_account_id IS NULL",
                        Long.class,
                        otherAccount.getId()))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM config_audit_event WHERE"
                                + " entity_type='WORKSPACE_ROLE' AND entity_id='ERASED' AND"
                                + " (old_value::jsonb)->>'role'='MEMBER' AND (new_value::jsonb)->>'role'='ADMIN'"
                                + " AND actor_account_id=?",
                        Long.class,
                        administrator.getId()))
                .isEqualTo(2L);
        for (long threadId : sharedThreads) {
            assertThat(jdbc.queryForObject(
                            "SELECT message_count FROM slack_thread WHERE id=?", Integer.class, threadId))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject(
                            "SELECT cardinality(participant_account_ids) FROM slack_thread" + " WHERE id=?",
                            Integer.class,
                            threadId))
                    .isEqualTo(1);
        }
        for (var copy : copiedFeedback) {
            MentorEntry erased =
                    mentorEntryRepository.findById(copy.messageId()).orElseThrow();
            assertThat(erased.isRedacted()).isTrue();
            assertThat(erased.getText()).isEmpty();
            assertThat(String.valueOf(erased.getPayload())).doesNotContain("Target guidance");
            assertThat(mentorEntryRepository
                            .findById(copy.replyId())
                            .orElseThrow()
                            .getText())
                    .isEqualTo("Other reply to erased feedback");
        }
        for (var contributor : registry.stores()) {
            // Only the Heph messages that copied the erased person's feedback change in another conversation.
            assertThat(withoutErasedCopies(
                            contributor.store(),
                            contributor.export(
                                    Objects.requireNonNull(otherSelection.get(contributor.store())), otherPrimaryScope),
                            copiedFeedback,
                            otherDerived.stream()
                                    .map(DerivedConversation::messageId)
                                    .toList()))
                    .as("Another person's %s rows", contributor.store())
                    .isEqualTo(withoutErasedCopies(
                            contributor.store(),
                            otherRows.get(contributor.store()),
                            copiedFeedback,
                            otherDerived.stream()
                                    .map(DerivedConversation::messageId)
                                    .toList()));
        }
        personData.requestErasure(requestId, administratorId, false);
        personData.run(requestId);
        assertThat(personData.get(requestId).request().getCompletedJson()).isEqualTo(receipt.getCompletedJson());
    }

    @Test
    void shouldRequireExactProviderKeysInsteadOfUsingSlackActorCaches() {
        IdentityProvider scm = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://cache-gitlab.example.org"));
        IdentityProvider slack =
                providers.saveAndFlush(new IdentityProvider(IdentityProviderType.SLACK, "https://slack.com"));
        User target = users.saveAndFlush(TestUserFactory.createUser(42L, "cached-person", scm));
        Workspace workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("cached-person"));
        membership(workspace, target);
        message(workspace, "TCACHE", "100.1", "100.1", "UCACHE", target, "Captured contribution");
        PersonIdentity gitlabKey = new PersonIdentity(Objects.requireNonNull(scm.getId()), "42", null);
        PersonIdentity slackKey = new PersonIdentity(Objects.requireNonNull(slack.getId()), "UCACHE", "TCACHE");
        var scmScope = resolver.resolve(null, List.of(gitlabKey));
        assertThat(scmScope.userIds()).containsExactly(target.getId());
        assertThat(scmScope.identities()).doesNotContain(slackKey);
        var slackScope = resolver.resolve(null, List.of(slackKey));
        assertThat(slackScope.userIds()).isEmpty();
        assertThat(slackScope.identities()).doesNotContain(gitlabKey);
        assertThat(resolver.resolve(null, List.of(gitlabKey, slackKey)).userIds())
                .containsExactly(target.getId());
    }

    @Test
    void shouldRejectErasureAuthorizedByAnAdministratorWhoseRoleWasRevokedAfterPreview() {
        IdentityProvider scm = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://role-gitlab.example.org"));
        User target = users.saveAndFlush(TestUserFactory.createUser(42L, "target", scm));
        Account administrator = new Account("Administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        administrator = accounts.saveAndFlush(administrator);
        long administratorId = Objects.requireNonNull(administrator.getId());
        var preview = personData.preview(
                administratorId, null, List.of(new PersonIdentity(Objects.requireNonNull(scm.getId()), "42", null)));
        administrator.setAppRole(Account.AppRole.USER);
        accounts.saveAndFlush(administrator);
        assertThatThrownBy(() -> personData.requestErasure(preview.request().getId(), administratorId, true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
        assertThat(personData.get(preview.request().getId()).request().getState())
                .isEqualTo(PersonDataRequest.State.PREVIEW);
        assertThat(suppression.isSuppressed(
                        Objects.requireNonNull(target.getProvider().getId()),
                        target.getNativeId().toString(),
                        null))
                .isFalse();
        assertThat(users.findById(target.getId()).orElseThrow().getLogin()).isEqualTo("target");
    }

    @Test
    void shouldPreserveActualAdministratorWhenAnotherAdministratorUsesThePersonsOwnPreview() {
        Account person = new Account("Person");
        person.setAppRole(Account.AppRole.APP_ADMIN);
        person = accounts.saveAndFlush(person);
        Account administrator = new Account("Administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        administrator = accounts.saveAndFlush(administrator);
        long personId = Objects.requireNonNull(person.getId());
        long administratorId = Objects.requireNonNull(administrator.getId());
        var preview = personData.preview(personId, personId, List.of());
        UUID requestId = preview.request().getId();
        assertThatThrownBy(() -> personData.requestErasure(requestId, personId, true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Another instance admin");
        personData.requestErasure(requestId, administratorId, true);
        personData.run(requestId);
        var receipt = personData.get(requestId).request();
        assertThat(receipt.getState()).isEqualTo(PersonDataRequest.State.COMPLETE);
        assertThat(receipt.getAdministratorAccountId()).isEqualTo(administratorId);
        assertThat(accounts.findById(administratorId).orElseThrow().getStatus()).isEqualTo(Account.Status.ACTIVE);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldEraseAffectedProofAndPreserveUnrelatedWorkWhenThePersonAppearsInCopiedEvidence(boolean linked) {
        var provider = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://secondary-evidence.example"));
        var target = users.saveAndFlush(TestUserFactory.createUser(42L, "target", provider));
        var other = users.saveAndFlush(TestUserFactory.createUser(84L, "OTHER-PROFILE-CANARY", provider));
        if (linked) accountFor(target);
        var administrator = new Account("Administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        long administratorId =
                Objects.requireNonNull(accounts.saveAndFlush(administrator).getId());
        List<DerivedConversation> affected = new ArrayList<>();
        List<UUID> copiedJobs = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("secondary-" + index));
            var derived = seedDerivedConversation(workspace, 1000L + index, other);
            affected.add(derived);
            copiedJobs.add(derived.jobId());
            var copiedResult = practiceResultRepository
                    .findById(derived.practiceResultIds().getFirst())
                    .orElseThrow();
            resultMarks.saveAndFlush(ResultMark.builder()
                    .id(UUID.randomUUID())
                    .workspaceId(copiedResult.getWorkspaceId())
                    .resultId(copiedResult.getId())
                    .createdById(administratorId)
                    .reason("OTHER-REVIEW-MARK-CANARY")
                    .createdAt(Instant.now())
                    .build());
            keepCompositionInputs(derived);
            // A removed folder must not hide the job that copied this person's data.
            jdbc.update(
                    """
                    INSERT INTO person_evidence_copy(id,workspace_id,job_id,state,payload)
                    VALUES (?,?,?,'ERASED',CAST(? AS jsonb))
                    """,
                    UUID.randomUUID(),
                    workspace.getId(),
                    derived.jobId(),
                    mapper.writeValueAsString(Map.of(
                            "identities",
                            List.of(new PersonCopyIdentity("GITLAB", provider.getServerUrl(), "42", null)),
                            "repositories",
                            List.of())));
        }
        var unrelatedWorkspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("secondary-keep"));
        var unrelated = seedDerivedConversation(unrelatedWorkspace, 2000L, other);
        keepCompositionInputs(unrelated);
        var preview = personData.preview(
                administratorId,
                null,
                List.of(new PersonIdentity(Objects.requireNonNull(provider.getId()), "42", null)));
        UUID requestId = preview.request().getId();
        var scope = mapper.readValue(Objects.requireNonNull(preview.request().getScopeJson()), PersonScope.class);
        assertThat(scope.derivedJobIds()).containsExactlyInAnyOrderElementsOf(copiedJobs);
        var export = personData.export(requestId);
        assertThat(export.toString())
                .doesNotContain(
                        "Result title",
                        "Practice guidance for developer",
                        "Delivered guidance",
                        "OTHER-REVIEW-MARK-CANARY");
        assertThat(export.path("stores").path("result"))
                .extracting(row -> row.path("id").asString())
                .containsExactlyInAnyOrderElementsOf(affected.stream()
                        .map(derived -> derived.practiceResultIds().getFirst().toString())
                        .toList());
        assertThat(export.path("stores").path("feedback"))
                .extracting(row -> row.path("id").asString())
                .containsExactlyInAnyOrderElementsOf(affected.stream()
                        .map(derived -> derived.preparedId().toString())
                        .toList());
        assertThat(export.path("stores").path("result_mark")).hasSize(2);
        assertThat(export.path("stores").path("person_evidence_copy"))
                .extracting(row -> row.path("jobId").asString())
                .containsExactlyInAnyOrderElementsOf(
                        copiedJobs.stream().map(UUID::toString).toList());
        Map<String, Long> counts = mapper.readValue(preview.request().getCountsJson(), new TypeReference<>() {});
        assertThat(counts.get("person_evidence_copy")).isEqualTo(2L);
        assertThat(counts.get("review_composition_basis")).isEqualTo(2L);
        assertThat(counts.get("result")).isEqualTo(2L);
        assertThat(counts.get("feedback")).isEqualTo(2L);
        assertThat(export.path("stores").path("review_composition_basis").toString())
                .doesNotContain("ORIGINAL-BASIS-CANARY");
        assertThat(export.path("stores").path("review_job"))
                .extracting(row -> row.path("id").asString())
                .containsExactlyInAnyOrderElementsOf(
                        copiedJobs.stream().map(UUID::toString).toList());
        assertThat(export.path("stores").path("review_job"))
                .allSatisfy(row -> assertThat(row.has("capture")).isFalse());
        assertThat(export.path("stores").path(MentorEntryRepository.COPIES_PERSON_DATA_STORE))
                .isEmpty();
        assertThat(export.toString())
                .doesNotContain(
                        "OTHER-PROFILE-CANARY",
                        "credential-canary",
                        "Practice guidance for developer",
                        "context/conversation.json");
        // The frozen selection names workspaces, so a tool result written after the preview is redacted too.
        Workspace firstAffected =
                workspaces.findById(affected.getFirst().workspaceId()).orElseThrow();
        UUID laterToolResult = seedToolResult(firstAffected, other);
        // The context folder's tools copy data as fetch_context did.
        List<UUID> folderResults = List.of(
                seedToolResult(firstAffected, other, "read"),
                seedToolResult(firstAffected, other, "bash"),
                seedToolResult(firstAffected, other, "load_context"));
        UUID linkedAnswer =
                seedFeedbackAnswer(firstAffected, other, affected.getFirst().deliveredId());
        personData.requestErasure(requestId, administratorId, true);
        personData.run(requestId);
        assertThat(personData.get(requestId).request().getState()).isEqualTo(PersonDataRequest.State.COMPLETE);
        for (var derived : affected) {
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM review_composition_basis WHERE job_id=?",
                            Integer.class,
                            derived.jobId()))
                    .isZero();
            assertThat(practiceResultRepository.findById(
                            derived.practiceResultIds().getFirst()))
                    .isEmpty();
            assertThat(practiceResultRepository.findById(
                            derived.practiceResultIds().getLast()))
                    .isPresent();
            assertThat(feedbackRepository.findById(derived.preparedId())).isEmpty();
            assertThat(feedbackRepository.findById(derived.deliveredId())).isPresent();
            var answer = mentorEntryRepository.findById(derived.messageId()).orElseThrow();
            assertThat(answer.isRedacted()).isTrue();
            assertThat(answer.getText()).isEmpty();
            assertThat(agentJobRepository.findById(derived.jobId())).isEmpty();
            assertThat(mentorEntryRepository
                            .findById(derived.toolResultId())
                            .orElseThrow()
                            .isRedacted())
                    .isTrue();
        }
        assertThat(mentorEntryRepository.findById(laterToolResult).orElseThrow().isRedacted())
                .isTrue();
        assertThat(folderResults)
                .allSatisfy(result -> assertThat(mentorEntryRepository
                                .findById(result)
                                .orElseThrow()
                                .isRedacted())
                        .as("a context folder result is erased with the person")
                        .isTrue());
        // A safe tool result stays; its answer could still have used the earlier copied context.
        var answer = mentorEntryRepository.findById(linkedAnswer).orElseThrow();
        assertThat(answer.isRedacted()).isTrue();
        assertThat(jdbc.queryForList(
                        "SELECT id FROM mentor_entry WHERE workspace_id=? AND turn_id=? AND kind='TOOL_RESULT'"
                                + " AND payload->'message'->>'toolName'='show_feedback' AND redacted_at IS NULL",
                        UUID.class,
                        firstAffected.getId(),
                        answer.getTurnId()))
                .hasSize(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM person_evidence_copy WHERE" + " payload<>'{}'::jsonb", Long.class))
                .isZero();
        assertThat(agentJobRepository.findById(unrelated.jobId()).orElseThrow().getCapture())
                .isNotNull();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM review_composition_basis WHERE job_id=?",
                        Integer.class,
                        unrelated.jobId()))
                .isEqualTo(1);
        assertThat(mentorEntryRepository
                        .findById(unrelated.toolResultId())
                        .orElseThrow()
                        .isRedacted())
                .isFalse();
        assertThat(users.findById(other.getId()).orElseThrow().getLogin()).isEqualTo("OTHER-PROFILE-CANARY");
        assertThat(suppression.isSuppressed(
                        Objects.requireNonNull(target.getProvider().getId()),
                        target.getNativeId().toString(),
                        null))
                .isTrue();
        personData.run(requestId);
        assertThat(personData.get(requestId).request().getState()).isEqualTo(PersonDataRequest.State.COMPLETE);
    }

    @Test
    void shouldDetachTheCallsOfATurnThatOpenedAfterThePreviewWhenTheErasureRuns() {
        var provider = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://heph-usage-erasure.example.test"));
        var target = users.saveAndFlush(TestUserFactory.createUser(42L, "heph-usage-erasure", provider));
        long accountId = Objects.requireNonNull(accountFor(target).getId());
        var administrator = new Account("Administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        administrator = accounts.saveAndFlush(administrator);
        long administratorId = Objects.requireNonNull(administrator.getId());
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("heph-usage-erasure"));
        long workspaceId = Objects.requireNonNull(workspace.getId());
        membership(workspace, target);
        var conversation = mentorConversations.findOrCreateMain(workspaceId, accountId);
        mentorTurns.submit(
                workspaceId,
                conversation.getId(),
                new MentorTurnService.Question(
                        MentorTurn.Surface.APP,
                        "How did my review go?",
                        UUID.randomUUID().toString(),
                        null,
                        null));
        MentorTurn turn = claims.claimNext().orElseThrow();
        var llm = LlmCatalogTestFixtures.admittedMentorConfig();
        var admitted = MentorTurnAdmission.of(llm, Objects.requireNonNull(llm.priceSnapshot()));

        // The preview freezes the claimed turn before any call of it has a row.
        long providerId = Objects.requireNonNull(provider.getId());
        var preview = personData.preview(administratorId, null, List.of(new PersonIdentity(providerId, "42", null)));
        UUID requestId = preview.request().getId();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM llm_usage_event WHERE source_id=?", Long.class, turn.getId()))
                .isZero();

        // The admission commits before the erasure suppresses the account; one call ends and one is still out.
        transactions.executeWithoutResult(tx -> mentorTurns.admit(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getRunId()),
                admitted.toJson()));
        UUID ended = openCall(
                workspaceId, turn.getRunId(), UsageSourceType.MENTOR_TURN, turn.getId(), null, admitted.price());
        completeCall(workspaceId, ended, 100_000, 0);
        UUID stillOut = openCall(
                workspaceId, turn.getRunId(), UsageSourceType.MENTOR_TURN, turn.getId(), null, admitted.price());

        personData.requestErasure(requestId, administratorId, false);
        personData.run(requestId);
        assertThat(personData.get(requestId).request().getState()).isEqualTo(PersonDataRequest.State.COMPLETE);

        // The turn's deletion settled the call still out; both rows keep their spend, detached together.
        String financial = "SELECT id, source_id, review_id, run_id, input_tokens, cost_usd, measurement_verification"
                + " FROM llm_usage_event WHERE workspace_id=? AND source_type='MENTOR_TURN' ORDER BY input_tokens DESC";
        var kept = jdbc.queryForList(financial, workspaceId);
        assertThat(kept).hasSize(2);
        assertThat(kept.getFirst().get("id")).isEqualTo(ended);
        assertThat(kept.getFirst().get("source_id")).isNotNull().isNotEqualTo(turn.getId());
        assertThat(kept.get(1).get("source_id")).isEqualTo(kept.getFirst().get("source_id"));
        assertThat(kept.getFirst().get("review_id")).isNull();
        assertThat(kept.getFirst().get("run_id"))
                .as("a deleted turn's run is gone")
                .isNull();
        assertThat(kept.getFirst().get("input_tokens")).isEqualTo(100_000L);
        assertThat((BigDecimal) kept.getFirst().get("cost_usd")).isEqualByComparingTo("1.00");
        assertThat(kept.get(1).get("id")).isEqualTo(stillOut);
        assertThat(kept.get(1).get("measurement_verification")).isEqualTo("UNVERIFIABLE");

        // The call still out returns after the erasure; it measures its detached row and names no turn.
        completeCall(workspaceId, stillOut, 50_000, 0);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM llm_usage_event WHERE source_id=?", Long.class, turn.getId()))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT input_tokens FROM llm_usage_event WHERE id=?", Long.class, stillOut))
                .isEqualTo(50_000L);
    }

    private void link(Account account, IdentityProvider provider, String subject, @Nullable String team) {
        Identity link = new Identity();
        link.setAccount(account);
        link.setProviderId(Objects.requireNonNull(provider.getId()));
        link.setProviderUserKey(subject);
        link.setTeamKey(Objects.requireNonNullElse(team, ""));
        links.saveAndFlush(link);
    }

    private Account accountFor(User user) {
        var existing = links.findActiveByProviderSubject(
                Objects.requireNonNull(user.getProvider().getId()),
                user.getNativeId().toString(),
                null);
        if (existing.isPresent()) return existing.get().getAccount();
        var account = accounts.saveAndFlush(new Account(user.getLogin()));
        link(account, user.getProvider(), user.getNativeId().toString(), null);
        return account;
    }

    private void membership(Workspace workspace, User user) {
        Membership membership = new Membership();
        membership.setWorkspace(workspace);
        membership.setId(new Membership.Id(workspace.getId(), accountFor(user).getId()));
        memberships.saveAndFlush(membership);
    }

    private SlackThread slackThread(Workspace workspace, String root, User... participants) {
        SlackThread thread = new SlackThread();
        thread.setWorkspaceId(workspace.getId());
        thread.setSlackChannelId("C1");
        thread.setSlackThreadTs(root);
        thread.setParticipantAccountIds(Arrays.stream(participants)
                .mapToLong(user -> Objects.requireNonNull(accountFor(user).getId()))
                .toArray());
        thread.setMessageCount(participants.length);
        return slackThreads.saveAndFlush(thread);
    }

    private void message(
            Workspace workspace,
            String team,
            String root,
            String timestamp,
            String nativeUser,
            User member,
            String text) {
        SlackMessage message = new SlackMessage();
        message.setWorkspaceId(workspace.getId());
        message.setSlackTeamId(team);
        message.setSlackChannelId("C1");
        message.setSlackThreadTs(root);
        message.setSlackTs(timestamp);
        message.setAuthorSlackUserId(nativeUser);
        message.setAuthorAccountId(accountFor(member).getId());
        message.setText(text);
        slackMessages.saveAndFlush(message);
    }

    private Path captureMountedEvidence(
            EvidenceFolderPersonDataCatalog worker,
            PersonDataCopyRecorder recorder,
            DerivedConversation derived,
            User owner)
            throws Exception {
        // The fixture ends the job; its evidence is captured by a run that is still executing.
        RuntimeJobTestData.updateJobs(
                jdbc,
                "UPDATE review_job SET status='RUNNING',completed_at=NULL,cancellation_reason=NULL WHERE id=?",
                derived.jobId());
        var job = agentJobRepository.findById(derived.jobId()).orElseThrow();
        var files = new JobEvidenceFiles(
                new FabricLayout(evidenceRoot.toString()),
                agentJobRepository,
                Clock.systemUTC(),
                worker,
                attemptInputs);
        files.beginPersonCapture(job);
        recorder.recordIdentity(new PersonCopyIdentity(
                owner.getProvider().getType().name(),
                owner.getProvider().getServerUrl(),
                owner.getNativeId().toString(),
                null));
        try (var prepared = files.prepare(
                job,
                new PreparedEvidence(
                        Map.of("context/person.json", "MOUNTED-PROFILE-CANARY".getBytes(StandardCharsets.UTF_8)), null),
                null)) {
            assertThat(prepared.filesOnDisk()).isNotEmpty();
        }
        return evidenceRoot
                .resolve("jobs")
                .resolve(Long.toString(job.getWorkspaceId()))
                .resolve(job.getId().toString());
    }

    private record DerivedConversation(
            List<UUID> practiceResultIds,
            UUID preparedId,
            UUID deliveredId,
            UUID messageId,
            UUID toolResultId,
            UUID jobId,
            long workspaceId) {}

    private record ConversationFeedbackCopy(UUID messageId, UUID replyId) {}

    /** The export of another person's rows without the Heph messages that the erasure redacts. */
    private static @Nullable Object withoutErasedCopies(
            String store, @Nullable Object rows, List<ConversationFeedbackCopy> copies, List<UUID> derivedAnswers) {
        if (!store.equals("mentor_entry") || !(rows instanceof List<?> list)) return rows;
        var copiedIds = Stream.concat(copies.stream().map(ConversationFeedbackCopy::messageId), derivedAnswers.stream())
                .map(UUID::toString)
                .collect(Collectors.toSet());
        return list.stream()
                .filter(row -> !(row instanceof JsonNode node
                        && copiedIds.contains(node.path("id").asString())))
                .toList();
    }

    /**
     * A Heph message in another person's conversation that carries the target's delivered feedback through a
     * conversation copy, and that person's reply to it. The claim path cannot write such a copy; the store still
     * redacts it, because the feedback ledger names the message.
     */
    private ConversationFeedbackCopy seedFeedbackCopy(DerivedConversation target, DerivedConversation other) {
        MentorEntry otherAnswer =
                mentorEntryRepository.findById(other.messageId()).orElseThrow();
        MentorEntry copied = mentorEntries.append(
                otherAnswer.getWorkspaceId(),
                otherAnswer.getConversationId(),
                entry(
                        MentorEntry.Kind.HEPH,
                        "Target guidance copied into another conversation",
                        piAnswer("Target guidance copied into another conversation")));
        MentorEntry reply = mentorEntries.append(
                otherAnswer.getWorkspaceId(),
                otherAnswer.getConversationId(),
                entry(MentorEntry.Kind.USER, "Other reply to erased feedback", null));
        UUID copyId = FeedbackIds.copy(target.deliveredId(), FeedbackCopyKind.CHAT_TURN, 1);
        feedbackCopyRepository.saveAndFlush(FeedbackCopy.builder()
                .id(copyId)
                .writeKey(copyId.toString())
                .writeMarker(FeedbackIds.writeMarker(copyId))
                .workspaceId(target.workspaceId())
                .feedbackId(target.deliveredId())
                .kind(FeedbackCopyKind.CHAT_TURN)
                .position(1)
                .state(FeedbackCopyState.POSTED)
                .body("Target guidance copied into another conversation")
                .writeStartedAt(Instant.now())
                .mentorEntryId(copied.getId())
                .postedAt(Instant.now())
                .createdAt(Instant.now())
                .build());
        return new ConversationFeedbackCopy(copied.getId(), reply.getId());
    }

    private static MentorEntryDraft entry(MentorEntry.Kind kind, String text, @Nullable JsonNode payload) {
        return new MentorEntryDraft(kind, MentorEntry.Surface.APP, text, payload, null, null, null);
    }

    /** Heph's answer as the end of its turn stores it, with a tool call whose arguments hold a credential. */
    private JsonNode piAnswer(String text) {
        return MentorLedgerFixtures.toolCall(
                MentorLedgerFixtures.piId(),
                "context-call",
                "fetch_context",
                mapper.createObjectNode().put("authorization", "credential-canary " + text));
    }

    private MentorConversation conversationOf(Workspace workspace, User owner) {
        return mentorConversations.findOrCreateMain(
                workspace.getId(), Objects.requireNonNull(accountFor(owner).getId()));
    }

    /** A tool result in the owner's conversation that read data without recording whose. */
    private UUID seedToolResult(Workspace workspace, User owner) {
        return seedToolResult(workspace, owner, "fetch_context");
    }

    private UUID seedToolResult(Workspace workspace, User owner, String tool) {
        return mentorEntries
                .append(
                        workspace.getId(),
                        conversationOf(workspace, owner).getId(),
                        entry(
                                MentorEntry.Kind.TOOL_RESULT,
                                "runtime-credential-canary unrelated-profile-canary",
                                MentorLedgerFixtures.toolResult(
                                        MentorLedgerFixtures.piId(),
                                        "context-call",
                                        tool,
                                        "runtime-credential-canary unrelated-profile-canary",
                                        false)))
                .getId();
    }

    /**
     * A Heph message in the owner's conversation that showed prepared feedback, with its successful
     * {@code show_feedback} result, which copies no one's data.
     */
    private UUID seedFeedbackAnswer(Workspace workspace, User owner, UUID feedbackId) {
        MentorConversation conversation = conversationOf(workspace, owner);
        UUID turnId = mentorTurns
                .submit(
                        workspace.getId(),
                        conversation.getId(),
                        new MentorTurnService.Question(
                                MentorTurn.Surface.APP,
                                "What did my review say?",
                                UUID.randomUUID().toString(),
                                null,
                                null))
                .turn()
                .getId();
        MentorEntry answer = mentorEntries.append(
                workspace.getId(),
                conversation.getId(),
                new MentorEntryDraft(
                        MentorEntry.Kind.HEPH,
                        MentorEntry.Surface.APP,
                        "",
                        MentorLedgerFixtures.toolCall(
                                MentorLedgerFixtures.piId(),
                                "show-call",
                                "show_feedback",
                                mapper.createObjectNode()
                                        .put("feedbackId", feedbackId.toString())
                                        .put("text", "Shown guidance")),
                        turnId,
                        null,
                        null));
        mentorEntries.append(
                workspace.getId(),
                conversation.getId(),
                new MentorEntryDraft(
                        MentorEntry.Kind.TOOL_RESULT,
                        MentorEntry.Surface.APP,
                        "",
                        MentorLedgerFixtures.toolResult(
                                MentorLedgerFixtures.piId(),
                                "show-call",
                                "show_feedback",
                                "Shown to the developer: feedback " + feedbackId,
                                false),
                        turnId,
                        null,
                        null));
        return answer.getId();
    }

    private DerivedConversation seedDerivedConversation(Workspace workspace, long threadId, User owner) {
        return seedDerivedConversation(workspace, threadId, owner, false);
    }

    private DerivedConversation seedDerivedConversation(
            Workspace workspace, long threadId, User owner, boolean withProblem) {
        UUID toolResult = seedToolResult(workspace, owner);
        MentorEntry message = mentorEntries.append(
                workspace.getId(),
                conversationOf(workspace, owner).getId(),
                entry(MentorEntry.Kind.HEPH, "Delivered guidance", piAnswer("Delivered guidance")));

        var work = modelFixtures.work(
                workspace, "conv-practice-" + workspace.getId() + "-" + owner.getId(), "chat.thread", threadId);
        var job = work.job();
        job.setCapture(mapper.valueToTree(Map.of(
                "sourcePath",
                "context/conversation.json",
                "author",
                owner.getNativeId().toString(),
                "privateCredential",
                "credential-canary",
                "sourceText",
                "unrelated-profile-canary")));
        job.setOutput(mapper.valueToTree(Map.of("sourceText", "unrelated-profile-canary")));
        agentJobRepository.save(job);
        var first = withProblem
                ? modelFixtures.resultWithProblem(
                        work,
                        Objects.requireNonNull(accountFor(owner).getId()),
                        "Result title",
                        PersonErasureAllStoresFixture.CANARY)
                : modelFixtures.result(
                        work, Objects.requireNonNull(accountFor(owner).getId()), "Result title");
        var second = modelFixtures.result(
                work, Objects.requireNonNull(accountFor(owner).getId()), "Result title");
        List<UUID> practiceResultIds = List.of(first.getId(), second.getId());
        var prepared = modelFixtures.feedback(
                first,
                FeedbackDestination.CONVERSATION,
                FeedbackState.READY,
                "Practice guidance for developer " + owner.getId());
        var delivered = modelFixtures.feedback(
                second,
                FeedbackDestination.CONVERSATION,
                FeedbackState.DELIVERED,
                "Practice guidance for developer " + owner.getId());
        UUID copyId = FeedbackIds.copy(delivered.getId(), FeedbackCopyKind.CHAT_TURN, 0);
        feedbackCopyRepository.save(FeedbackCopy.builder()
                .id(copyId)
                .writeKey(copyId.toString())
                .writeMarker(FeedbackIds.writeMarker(copyId))
                .workspaceId(workspace.getId())
                .feedbackId(delivered.getId())
                .kind(FeedbackCopyKind.CHAT_TURN)
                .state(FeedbackCopyState.POSTED)
                .body("Delivered guidance")
                .writeStartedAt(Instant.now())
                .mentorEntryId(message.getId())
                .postedAt(Instant.now())
                .createdAt(Instant.now())
                .build());
        return new DerivedConversation(
                practiceResultIds,
                prepared.getId(),
                delivered.getId(),
                message.getId(),
                toolResult,
                job.getId(),
                workspace.getId());
    }
}
