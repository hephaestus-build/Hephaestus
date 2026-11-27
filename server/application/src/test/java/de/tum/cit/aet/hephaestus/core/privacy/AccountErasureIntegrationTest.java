package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.activity.ActivityFact;
import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceCaptures;
import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog;
import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceReceipts;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.context.AttemptInputs;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.context.ReviewAttemptCustody;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationAdmission;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationRefusal;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationTurnAdmission;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobLifecycleService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.conversation.Conversation;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryDraft;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerFixtures;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnClaims;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnContextStore;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.auth.export.AccountExport;
import de.tum.cit.aet.hephaestus.core.auth.export.AccountExportRepository;
import de.tum.cit.aet.hephaestus.core.auth.export.ExportGenerationWorker;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OutlinePersonCopyScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournal;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournalKind;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.consumer.ProviderEventStore;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.handler.ProviderEvent;
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
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
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
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class AccountErasureIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ConversationTurnClaims claims;

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
    private ProviderEventStore providerEvents;

    @Autowired
    private PersonDataService personData;

    @Autowired
    private ApplicationContext beans;

    @Autowired
    private ExportGenerationWorker exportWorker;

    @Autowired
    private AccountExportRepository accountExports;

    @Autowired
    private OwnerScope ownerScope;

    @Autowired
    private PrivacyJournal journal;

    @Autowired
    private PracticeVersionService practiceVersions;

    @Autowired
    private PersonProcessingSuppression suppression;

    @Autowired
    private ConversationAdmission conversation;

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
    private ConversationService conversations;

    @Autowired
    private ConversationEntryService conversationEntries;

    @Autowired
    private ConversationEntryRepository conversationEntryRepository;

    @Autowired
    private ConversationTurnService conversationTurns;

    @Autowired
    private ConversationTurnContextStore turnContext;

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
            "privacy_journal",
            "person_data_request",
            "person_evidence_copy",
            "slack_thread",
            "outline_document_content",
            // The frozen selection names workspaces, not entries.
            ConversationEntryRepository.COPIES_PERSON_DATA_STORE);

    @BeforeEach
    void clearRows() {
        databaseTestUtils.cleanDatabase();
    }

    @ParameterizedTest
    @EnumSource(
            value = IntegrationKind.class,
            names = {"GITHUB", "GITLAB", "SLACK", "OUTLINE"})
    void shouldEraseDeliveriesThatArriveBetweenPreviewAdmissionAndExecution(IntegrationKind kind) {
        eraseArrivals(kind, false);
    }

    @ParameterizedTest
    @EnumSource(
            value = IntegrationKind.class,
            names = {"GITHUB", "GITLAB", "SLACK", "OUTLINE"})
    void shouldEraseDeliveriesThatArriveBeforeAFailedRequestIsResumed(IntegrationKind kind) {
        eraseArrivals(kind, true);
    }

    /**
     * Each erasure step commits on its own. A withdrawal hides its feedback's text, also from a new account export, so
     * the step that deletes a withdrawal never commits before the one that deletes its feedback.
     */
    @Test
    void shouldEraseFeedbackDecisionsNoEarlierThanTheirFeedback() {
        var order = registry.stores().stream().map(PersonDataContributor::store).toList();
        assertThat(order.indexOf("feedback")).isNotNegative();
        assertThat(order.indexOf("feedback_decision")).isGreaterThan(order.indexOf("feedback"));
    }

    @Test
    void shouldKeepWithdrawnWordsOutOfFreshExportsBetweenCommittedErasureSteps() {
        var admin = new Account("Erasure operator");
        admin.setAppRole(Account.AppRole.APP_ADMIN);
        long operator = Objects.requireNonNull(accounts.saveAndFlush(admin).getId());
        long developer = Objects.requireNonNull(
                accounts.saveAndFlush(new Account("Feedback recipient")).getId());
        var provider = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://withdrawal-erasure.example.test"));
        users.saveAndFlush(TestUserFactory.createUser(42L, "erasure-subject", provider));
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("withdrawal-erasure"));
        var affected = modelFixtures.work(workspace, "affected", "scm.pull_request", 71L);
        var kept = modelFixtures.work(workspace, "kept", "scm.pull_request", 72L);
        String withdrawnWords = "Feedback withdrawn before erasure.";
        String keptWords = "Unrelated feedback for the same recipient.";
        var withdrawn = modelFixtures.feedback(
                modelFixtures.result(affected, developer, "Affected result"),
                FeedbackDestination.PRACTICE_PAGE,
                FeedbackState.DELIVERED,
                withdrawnWords);
        modelFixtures.feedback(
                modelFixtures.result(kept, developer, "Unrelated result"),
                FeedbackDestination.PRACTICE_PAGE,
                FeedbackState.DELIVERED,
                keptWords);
        jdbc.update(
                """
                INSERT INTO person_evidence_copy(id,workspace_id,job_id,state,payload)
                VALUES (?,?,?,'ERASED',CAST(? AS jsonb))
                """,
                UUID.randomUUID(),
                workspace.getId(),
                affected.job().getId(),
                mapper.writeValueAsString(Map.of(
                        "identities", List.of(new PersonCopyIdentity("GITLAB", provider.getServerUrl(), "42", null)),
                        "repositories", List.of())));
        assertThat(freshAccountExport(developer)).contains(withdrawnWords, keptWords);

        jdbc.update("""
                INSERT INTO feedback_decision(id,workspace_id,feedback_id,action,actor_account_id,note)
                VALUES (?,?,?,'WITHDRAW',?,'Withdraw the affected feedback')
                """, UUID.randomUUID(), workspace.getId(), withdrawn.getId(), operator);
        var preview = personData.preview(
                operator, null, List.of(new PersonIdentity(Objects.requireNonNull(provider.getId()), "42", null)));
        UUID requestId = preview.request().getId();
        var selection = mapper.readTree(preview.request().getCountsJson());
        assertThat(selection.path("feedback").asLong()).isOne();
        assertThat(selection.path("feedback_decision").asLong()).isOne();
        personData.requestErasure(requestId, operator, false);

        // The service asks for journal proof after each committed step. Stop just before the feedback step.
        var stores =
                registry.stores().stream().map(PersonDataContributor::store).toList();
        String previousStore = stores.get(stores.indexOf("feedback") - 1);
        var shipping = mock(PrivacyJournalShipping.class);
        when(shipping.erasurePending(requestId))
                .thenAnswer(ignored -> Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT jsonb_exists(completed_json::jsonb, ?) FROM person_data_request WHERE id=?",
                        Boolean.class,
                        previousStore,
                        requestId)));
        erasureWithShipping(shipping).run(requestId);

        var paused = personData.get(requestId).request();
        assertThat(paused.getState()).isEqualTo(PersonDataRequest.State.ERASING);
        assertThat(PersonDataStoreReceipt.counts(mapper, paused.getCompletedJson()))
                .containsKey(previousStore)
                .doesNotContainKey("feedback");
        assertThat(freshAccountExport(developer))
                .contains(keptWords, withdrawn.getId().toString())
                .doesNotContain(withdrawnWords);

        personData.run(requestId);
        assertThat(personData.get(requestId).request().getState()).isEqualTo(PersonDataRequest.State.COMPLETE);
        assertThat(feedbackRepository.findByIdAndWorkspaceId(withdrawn.getId(), withdrawn.getWorkspaceId()))
                .isEmpty();
        assertThat(freshAccountExport(developer))
                .contains(keptWords)
                .doesNotContain(withdrawnWords, withdrawn.getId().toString());
    }

    private PersonDataService erasureWithShipping(PrivacyJournalShipping shipping) {
        var service = new PersonDataService(
                beans.getBean(PersonDataScopeResolver.class),
                registry,
                beans.getBean(PersonDataRequestRepository.class),
                beans.getBean(PersonDataErasureHold.class),
                issuedTokens,
                beans.getBean(PlatformTransactionManager.class),
                mapper,
                jdbc,
                beans.getBean(MeterRegistry.class),
                journal,
                shipping);
        return PersonDataService.class.cast(
                beans.getAutowireCapableBeanFactory().initializeBean(service, "personDataWithPausedJournal"));
    }

    private String freshAccountExport(long accountId) {
        long exportId = Objects.requireNonNull(transactions.execute(status -> {
            ownerScope.ofJob(accountId);
            return accountExports.save(new AccountExport(accountId)).getId();
        }));
        exportWorker.generate(exportId, accountId);
        var export = Objects.requireNonNull(transactions.execute(status -> {
            ownerScope.ofJob(accountId);
            return accountExports.findByIdAndAccountId(exportId, accountId).orElseThrow();
        }));
        assertThat(export.getStatus()).isEqualTo(AccountExport.Status.READY);
        return new String(Objects.requireNonNull(export.getPayload()), StandardCharsets.UTF_8);
    }

    @Test
    void shouldExportAndEraseAnOidcAccountWithoutTouchingTheSameSubjectAtAnotherIssuerOnTheHost() {
        var administrator = new Account("OIDC erasure administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        long administratorId =
                Objects.requireNonNull(accounts.saveAndFlush(administrator).getId());
        // An issuer can have a path, and its subject is an opaque string that two issuers can both use.
        String subject = "ada";
        var school = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.OIDC, "https://login.erasure.example.test/realms/school"));
        var other = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.OIDC, "https://login.erasure.example.test/realms/other"));
        long erased = oidcAccount("OIDC person", school, subject);
        long kept = oidcAccount("Same subject at another issuer", other, subject);

        assertThat(freshAccountExport(erased)).contains("OIDC");

        var preview = personData.preview(administratorId, erased, List.of());
        personData.requestErasure(preview.request().getId(), administratorId, true);
        personData.run(preview.request().getId());

        assertThat(personData.get(preview.request().getId()).request().getState())
                .isEqualTo(PersonDataRequest.State.COMPLETE);
        assertThat(links.findByAccount_Id(erased)).isEmpty();
        assertThat(suppression.isSuppressed(Objects.requireNonNull(school.getId()), subject, null))
                .isTrue();
        assertThat(suppression.isSuppressed(Objects.requireNonNull(other.getId()), subject, null))
                .isFalse();
        assertThat(suppression.isAccountSuppressed(kept)).isFalse();
        assertThat(links.findByAccount_Id(kept))
                .extracting(Identity::getProviderUserKey)
                .containsExactly(subject);
    }

    private long oidcAccount(String name, IdentityProvider issuer, String subject) {
        var account = accounts.saveAndFlush(new Account(name));
        var identity = new Identity();
        identity.setAccount(account);
        identity.setProviderId(Objects.requireNonNull(issuer.getId()));
        identity.setProviderUserKey(subject);
        links.saveAndFlush(identity);
        return Objects.requireNonNull(account.getId());
    }

    @Test
    void shouldCompleteAnErasureAdmittedBeforeTheAdviceStoresWereAdded() {
        var admin = new Account("Rolling upgrade erasure operator");
        admin.setAppRole(Account.AppRole.APP_ADMIN);
        long adminId = Objects.requireNonNull(accounts.saveAndFlush(admin).getId());
        long subject = Objects.requireNonNull(accounts.saveAndFlush(new Account("Subject of an in-flight erasure"))
                .getId());
        var preview = personData.preview(adminId, subject, List.of());
        UUID id = preview.request().getId();
        personData.requestErasure(id, adminId, true);

        // An older replica had the same scope and admission hold, with no advice keys in its inventory.
        var oldSelection =
                mapper.readValue(Objects.requireNonNull(preview.request().getSelectionsJson()), ObjectNode.class);
        oldSelection.remove(List.of("practice_advice", "practice_advice_revision", "practice_advice_source"));
        jdbc.update("UPDATE person_data_request SET selections_json=? WHERE id=?", oldSelection.toString(), id);

        personData.run(id);
        assertThat(personData.get(id).request().getState()).isEqualTo(PersonDataRequest.State.COMPLETE);
    }

    @Test
    void shouldKeepTheErasurePreviewValidAfterReplayRetentionDeletesTheRow() {
        Account administrator = new Account("Replay erasure administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        long administratorId =
                Objects.requireNonNull(accounts.saveAndFlush(administrator).getId());
        long person = Objects.requireNonNull(
                accounts.saveAndFlush(new Account("Person with a replay")).getId());
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("replayed-retention"));
        String delivery = inboxArrival(workspace.getId(), IntegrationKind.GITHUB, "https://github.com", "owner", "{}");
        jdbc.update(
                "UPDATE provider_event SET replayed_by=?,state='DONE',ended_at=clock_timestamp()-interval '4 days'"
                        + " WHERE delivery_key=?",
                person,
                delivery);
        var preview = personData.preview(administratorId, person, List.of());
        providerEvents.deleteEnded(
                Instant.now().minus(3, ChronoUnit.DAYS), Instant.now().minus(30, ChronoUnit.DAYS));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM provider_event WHERE delivery_key=?", Long.class, delivery))
                .isZero();
        personData.requestErasure(preview.request().getId(), administratorId, true);
        personData.run(preview.request().getId());
        assertThat(personData.get(preview.request().getId()).request().getState())
                .isEqualTo(PersonDataRequest.State.COMPLETE);
    }

    private void eraseArrivals(IntegrationKind kind, boolean resume) {
        var administrator = new Account("Inbox erasure administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        long administratorId =
                Objects.requireNonNull(accounts.saveAndFlush(administrator).getId());
        String origin = kind == IntegrationKind.SLACK ? "https://slack.com" : "https://inbox-erasure.example.test";
        var provider = providers.saveAndFlush(new IdentityProvider(IdentityProviderType.valueOf(kind.name()), origin));
        var first = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("inbox-erasure-first"));
        var second = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("inbox-erasure-second"));
        String subject = kind == IntegrationKind.GITHUB || kind == IntegrationKind.GITLAB ? "42" : "U42";
        String body = mapper.writeValueAsString(
                switch (kind) {
                    case GITHUB -> Map.of("sender", Map.of("id", 42, "login", "person"), "text", "private");
                    case GITLAB ->
                        Map.of("user_id", 42, "object_attributes", Map.of("author_id", 42), "text", "private");
                    default -> Map.of("user", "U42", "text", "private");
                });
        String frozen = inboxArrival(first.getId(), kind, origin, "T1", body);
        String foreign = inboxArrival(second.getId(), kind, "https://another-source.example.test", "T1", body);
        String anotherTeam =
                inboxArrival(second.getId(), kind, origin, "T2", kind == IntegrationKind.SLACK ? body : "{}");
        var preview = personData.preview(
                administratorId,
                null,
                List.of(new PersonIdentity(
                        Objects.requireNonNull(provider.getId()),
                        subject,
                        kind == IntegrationKind.SLACK ? "T1" : null)));
        UUID request = preview.request().getId();
        String beforeAdmission = inboxArrival(second.getId(), kind, origin, "T1", body);
        personData.requestErasure(request, administratorId, true);
        String afterAdmission = inboxArrival(first.getId(), kind, origin, "T1", body);
        List<String> erasedDeliveries = new ArrayList<>(List.of(frozen, beforeAdmission, afterAdmission));
        if (resume) {
            // The inbox step committed, then a later store failed. Recovery must run the live predicate again.
            jdbc.update(
                    "DELETE FROM provider_event WHERE delivery_key IN (?,?,?)",
                    frozen,
                    beforeAdmission,
                    afterAdmission);
            String completed = mapper.writeValueAsString(
                    Map.of("provider_event", new PersonDataStoreReceipt(3, Instant.now(), administratorId)));
            jdbc.update(
                    "UPDATE person_data_request SET"
                            + " state='FAILED',failure_code='STORE_ERASURE_FAILED',completed_json=? WHERE id=?",
                    completed,
                    request);
            erasedDeliveries.add(inboxArrival(second.getId(), kind, origin, "T1", body));
            personData.requestErasure(request, administratorId, true);
        }
        personData.run(request);
        var erasedRequest = personData.get(request).request();
        assertThat(erasedRequest.getState()).isEqualTo(PersonDataRequest.State.COMPLETE);
        assertThat(PersonDataStoreReceipt.counts(mapper, erasedRequest.getCompletedJson())
                        .get("provider_event"))
                .isEqualTo(resume ? 4L : 3L);
        for (String erased : erasedDeliveries) {
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM provider_event WHERE delivery_key=?", Long.class, erased))
                    .isZero();
        }
        for (String kept : List.of(foreign, anotherTeam)) {
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM provider_event WHERE delivery_key=?", Long.class, kept))
                    .isEqualTo(1);
        }
    }

    private String inboxArrival(long workspace, IntegrationKind kind, String origin, String team, String body) {
        String delivery = UUID.randomUUID().toString();
        String provider = kind.name().toLowerCase(Locale.ROOT);
        providerEvents.store(List.of(new ProviderEventStore.Arrival(
                workspace,
                provider,
                provider + "." + team + ".channel.message",
                delivery,
                Map.of(ProviderEvent.SOURCE_SERVER_URL, origin),
                body.getBytes(StandardCharsets.UTF_8))));
        return delivery;
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
        var export = PersonDataBundles.of(
                personData, Objects.requireNonNull(transactions.getTransactionManager()), requestId);
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
                        + " (c.config->'orgLogin') IS NOT NULL AS has_login FROM connection c JOIN workspace w ON"
                        + " w.id=c.workspace_id WHERE c.id=?",
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
                        namespaceReservations.slugToken("personal-install"),
                        namespaceReservations.slugToken("target-login"),
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
                new PracticeVersionService.NewPractice(workspace.getId(), slug, kind, definition, null, 0, accountId));
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
                "UPDATE feedback_copy SET state='POSTED',posted_at=CURRENT_TIMESTAMP,external_ref='note-99' WHERE"
                        + " feedback_id=?",
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

    @Test
    void shouldDeleteTheSelectedJobsComposerTranscriptAndExportNoneOfIt() {
        var provider = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://composer-transcript.example.test"));
        var target = users.saveAndFlush(TestUserFactory.createUser(42L, "composer-transcript", provider));
        var administrator = new Account("Administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        long administratorId =
                Objects.requireNonNull(accounts.saveAndFlush(administrator).getId());
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("composer-transcript"));
        long workspaceId = Objects.requireNonNull(workspace.getId());
        var work = modelFixtures.work(workspace, "composer-transcript-" + workspaceId, "scm.pull_request", 42L);
        modelFixtures.result(work, Objects.requireNonNull(accountFor(target).getId()), "Composed result");
        UUID runId = Objects.requireNonNull(work.job().getRunId());
        var otherWorkspace =
                workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("composer-transcript-other"));
        long otherWorkspaceId = Objects.requireNonNull(otherWorkspace.getId());
        var otherWork = modelFixtures.work(otherWorkspace, "composer-transcript-other", "scm.pull_request", 77L);
        UUID otherRun = Objects.requireNonNull(otherWork.job().getRunId());
        transcriptPart(workspaceId, runId, "SERVED", "ANSWER-NAMES-THE-DEVELOPER-CANARY");
        transcriptPart(workspaceId, runId, "REPLY", null);
        transcriptPart(otherWorkspaceId, otherRun, "SERVED", "OTHER-WORKSPACE-ANSWER");

        long providerId = Objects.requireNonNull(provider.getId());
        var preview = personData.preview(administratorId, null, List.of(new PersonIdentity(providerId, "42", null)));
        UUID requestId = preview.request().getId();
        Map<String, Long> counts = mapper.readValue(preview.request().getCountsJson(), new TypeReference<>() {});
        assertThat(counts.get("composer_transcript_part")).isEqualTo(2L);
        var export = PersonDataBundles.of(
                personData, Objects.requireNonNull(transactions.getTransactionManager()), requestId);
        assertThat(export.path("stores").path("composer_transcript_part")).isEmpty();
        assertThat(export.toString()).doesNotContain("ANSWER-NAMES-THE-DEVELOPER-CANARY");
        // A claim that still runs adds a part after the preview; the erasure deletes it too.
        transcriptPart(workspaceId, runId, "TEXT", null);

        personData.requestErasure(requestId, administratorId, false);
        personData.run(requestId);

        assertThat(personData.get(requestId).request().getState()).isEqualTo(PersonDataRequest.State.COMPLETE);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM composer_transcript_part WHERE workspace_id=?", Long.class, workspaceId))
                .isZero();
        assertThat(jdbc.queryForList(
                        "SELECT convert_from(answer, 'UTF8') FROM composer_transcript_part WHERE workspace_id=?",
                        String.class,
                        otherWorkspaceId))
                .as("another workspace's transcript stays")
                .containsExactly("OTHER-WORKSPACE-ANSWER");
    }

    private void transcriptPart(long workspaceId, UUID runId, String kind, @Nullable String answer) {
        jdbc.update(
                "INSERT INTO composer_transcript_part (id, workspace_id, run_id, lease_epoch, kind, digest, answer)"
                        + " VALUES (gen_random_uuid(), ?, ?, 1, ?, ?, ?)",
                workspaceId,
                runId,
                kind,
                UUID.randomUUID().toString(),
                answer == null ? null : answer.getBytes(StandardCharsets.UTF_8));
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
                sourceType == UsageSourceType.CONVERSATION_TURN ? "CONVERSATION_TURN" : "PRACTICE_REVIEW",
                sourceType,
                sourceId,
                Math.max(0, sourceType == UsageSourceType.CONVERSATION_TURN ? epoch : epoch - 1),
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

    private void keepWorkPlaceHandoff(DerivedConversation derived) {
        jdbc.update("""
            INSERT INTO work_place_handoff(id,workspace_id,review_id,job_id,item_id,producer,payload,payload_digest,
                expires_at,created_at)
            SELECT ?,j.workspace_id,j.review_id,j.id,r.item_id,'LEGACY',
                '{"files":{"record":"WORK-PLACE-HANDOFF-CANARY"}}'::jsonb,?,
                CURRENT_TIMESTAMP + INTERVAL '1 hour',CURRENT_TIMESTAMP
            FROM review_job j JOIN review r ON (r.id,r.workspace_id)=(j.review_id,j.workspace_id)
            WHERE j.id=? AND j.workspace_id=?
            """, UUID.randomUUID(), "c".repeat(64), derived.jobId(), derived.workspaceId());
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
              'ADMITTED',jsonb_build_object('feedbackId',f.id),f.person_account_id,8,CURRENT_TIMESTAMP + INTERVAL '1 day',
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
        var nonceWorkspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("privacy-nonce"));
        var targetOAuthBinding = new OAuthStateService.StateBinding(
                nonceWorkspace.getId(),
                IntegrationKind.GITHUB,
                Instant.now().truncatedTo(ChronoUnit.SECONDS),
                targetAccount.getId());
        oauthNonces.issue(
                "nonce-credential-canary",
                nonceWorkspace.getId(),
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
            jdbc.update(
                    """
                    INSERT INTO mcp_client_grant(id,workspace_id,account_id,client_id,client_name,redirect_uri,resource,
                        scopes,code_challenge,state,status,client_session_id,expires_at,created_at,approved_at)
                    VALUES (?,?,?,'https://client.example.test/client.json','Privacy client',
                        'http://127.0.0.1:53111/callback','https://hephaestus.example.test/api/workspaces/privacy-nonce/mcp',
                        'practices:read','pkce-credential-canary','state-credential-canary','ACTIVE',?,
                        CURRENT_TIMESTAMP+INTERVAL '1 hour',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """,
                    UUID.randomUUID(),
                    nonceWorkspace.getId(),
                    entry.getValue().getId(),
                    entry.getKey());
        }
        User target = users.saveAndFlush(TestUserFactory.createUser(42L, "target", scm));
        User other = users.saveAndFlush(TestUserFactory.createUser(84L, "other", scm));
        link(targetAccount, scm, "42", null);
        link(otherAccount, scm, "84", null);
        var recorder = new ExactPersonDataCopyRecorder();
        var evidenceLayout = new FabricLayout(evidenceRoot.toString());
        var workerReceipts = new EvidenceReceipts(evidenceLayout, jdbc, mapper, copyFence);
        var workerCaptures = new EvidenceCaptures(evidenceLayout, jdbc, mapper, recorder, copyFence, workerReceipts);
        var worker = new EvidenceFolderPersonDataCatalog(
                evidenceLayout,
                jdbc,
                namedJdbc,
                mapper,
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
            targetFolders.add(captureMountedEvidence(
                    worker, workerReceipts, workerCaptures, recorder, targetDerived.getLast(), target));
            otherFolders.add(captureMountedEvidence(
                    worker, workerReceipts, workerCaptures, recorder, otherDerived.getLast(), other));
        }
        Stream.concat(targetDerived.stream(), otherDerived.stream()).forEach(derived -> {
            keepCompositionInputs(derived);
            keepWorkPlaceHandoff(derived);
        });
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
        recordMixedContext(otherDerived.getFirst());
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
        var ownConversation = conversations.findOrCreateMain(firstWorkspace, subject.account());
        UUID ownOutlineCopy = conversationEntries
                .append(
                        firstWorkspace,
                        ownConversation.getId(),
                        entry(
                                ConversationEntry.Kind.TOOL_RESULT,
                                "",
                                ConversationLedgerFixtures.toolResult(
                                        ConversationLedgerFixtures.piId(),
                                        "outline",
                                        "read",
                                        PersonErasureAllStoresFixture.CANARY,
                                        false)))
                .getId();
        conversationTurns.submit(
                firstWorkspace,
                ownConversation.getId(),
                new ConversationTurnService.Question(
                        ConversationTurn.Surface.APP,
                        "Retained question",
                        UUID.randomUUID().toString(),
                        null,
                        null));
        var ownedTurn = claims.claimNext().orElseThrow();
        var model = LlmCatalogTestFixtures.admittedConversationConfig();
        transactions.executeWithoutResult(tx -> conversationTurns.admit(
                firstWorkspace,
                ownedTurn.getId(),
                ownedTurn.getAttempt(),
                Objects.requireNonNull(ownedTurn.getRunId()),
                ConversationTurnAdmission.of(model, Objects.requireNonNull(model.priceSnapshot()))
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
        allStores.seedRoleChangeNotifications(subject.account(), Objects.requireNonNull(otherAccount.getId()));
        // A trial that read the person's work: a copy of one of their reviews, ended with its membership tried.
        UUID source = jdbc.queryForObject("""
                        SELECT r.id FROM review r WHERE r.kind='ITEM' AND EXISTS (SELECT 1 FROM result x
                         WHERE (x.review_id, x.workspace_id) = (r.id, r.workspace_id) AND x.person_account_id = ?)
                         ORDER BY r.id LIMIT 1
                        """, UUID.class, targetAccount.getId());
        UUID trial = UUID.randomUUID();
        assertThat(jdbc.update("""
                        INSERT INTO review SELECT (jsonb_populate_record(NULL::review, to_jsonb(r)
                          || jsonb_build_object('id', ?::text, 'origin', 'TRIAL', 'event_id', NULL, 'batch_id', NULL,
                                                'request_key', NULL, 'request_digest', NULL, 'requested_by_id', NULL, 'status', 'COMPLETE',
                                                'stop_reason', NULL, 'report', '{"schemaVersion":1,"candidates":[]}'::jsonb))).*
                          FROM review r WHERE r.id = ?
                        """, trial.toString(), source)).isOne();
        assertThat(jdbc.update("""
                        INSERT INTO review_practice SELECT (jsonb_populate_record(NULL::review_practice, to_jsonb(p)
                          || jsonb_build_object('review_id', ?::text))).*
                          FROM review_practice p WHERE p.review_id = ?
                        """, trial.toString(), source)).isPositive();
        assertThat(jdbc.update("""
                        INSERT INTO review_item SELECT (jsonb_populate_record(NULL::review_item, to_jsonb(m)
                          || jsonb_build_object('review_id', ?::text, 'state', 'TRIED', 'result_id', NULL, 'job_id', NULL,
                                                'staged_payload', NULL, 'staged_attempt', NULL,
                                                'detail', jsonb_build_object('developerId', ?::bigint)))).*
                          FROM review_item m WHERE m.review_id = ?
                        """, trial.toString(), targetAccount.getId(), source))
                .isPositive();
        // The composer transcript of one of the person's jobs: a digest, as a run keeps it after its claim ended.
        assertThat(jdbc.update("""
                        INSERT INTO composer_transcript_part (id, workspace_id, run_id, lease_epoch, kind, digest)
                        SELECT gen_random_uuid(), j.workspace_id, j.run_id, 1, 'SERVED', 'all-stores-transcript'
                          FROM review_job j
                         WHERE EXISTS (SELECT 1 FROM result x
                                        WHERE (x.review_id, x.workspace_id) = (j.review_id, j.workspace_id)
                                          AND x.person_account_id = ?)
                         ORDER BY j.id LIMIT 1
                        """, targetAccount.getId())).isOne();
        // The person's personal space, which only its owner can enter.
        Long personalSpace = jdbc.queryForObject(
                "INSERT INTO workspace (slug, display_name, account_login, account_type, status, kind,"
                        + " personal_account_id) VALUES ('me--aaaaaaaaaaaaaaaa', 'Personal space',"
                        + " 'me--aaaaaaaaaaaaaaaa', 'USER', 'ACTIVE', 'PERSONAL', ?) RETURNING id",
                Long.class,
                targetAccount.getId());
        jdbc.update(
                "INSERT INTO membership (workspace_id, account_id, role, role_source) VALUES (?, ?, 'MEMBER', 'PERSON')",
                personalSpace,
                targetAccount.getId());
        long administratorId = Objects.requireNonNull(administrator.getId());
        long targetId = Objects.requireNonNull(targetAccount.getId());
        long recoveryWorkspaceId = Objects.requireNonNull(nonceWorkspace.getId());
        UUID recoveryEntry = UUID.randomUUID();
        transactions.executeWithoutResult(status -> journal.record(
                recoveryEntry,
                PrivacyJournalKind.MEMBERSHIP_ENDED,
                Map.of("workspaceId", recoveryWorkspaceId, "accountId", targetId)));
        var preview = personData.preview(administratorId, targetId, List.of());
        UUID requestId = preview.request().getId();
        PersonScope frozen =
                mapper.readValue(Objects.requireNonNull(preview.request().getScopeJson()), PersonScope.class);
        var export = PersonDataBundles.of(
                personData, Objects.requireNonNull(transactions.getTransactionManager()), requestId);
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
        assertThat(counts.get("mcp_client_grant")).isEqualTo(1L);
        assertThat(export.path("stores").path("mcp_client_grant").toString())
                .doesNotContain("code_challenge", "state-credential-canary");
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
        // The feedback on the work has the copy that the fixture's thread resolution goes with.
        assertThat(export.path("stores").path("feedback_copy"))
                .extracting(row -> row.path("feedback_id").asString())
                .containsExactlyInAnyOrderElementsOf(Stream.concat(
                                targetDerived.stream()
                                        .flatMap(derived -> Stream.of(derived.deliveredId(), derived.deliveredId())),
                                Stream.of(onWork.getId()))
                        .map(UUID::toString)
                        .toList());
        // One of them is the earlier result that the fixture's problem lineage compares with.
        assertThat(counts.get("result")).isEqualTo(5L);
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
        assertThat(counts.get("conversation")).isEqualTo(2L);
        assertThat(counts.get("conversation_entry")).isEqualTo(3L);
        // The two team workspaces, and the personal space, whose sandboxes hold the person's own copies.
        assertThat(counts.get(ConversationEntryRepository.COPIES_PERSON_DATA_STORE))
                .isEqualTo(3L);
        assertThat(counts.get("conversation_entry_feedback_copy")).isEqualTo(2L);
        assertThat(export.path("stores")
                        .path("conversation_entry_feedback_copy")
                        .toString())
                .contains("Target guidance copied into another conversation")
                .doesNotContain("Other reply to erased feedback", "account_id", "credential-canary");
        assertThat(counts.get("slack_message")).isEqualTo(2L);
        // Tool results in another member's conversation are redacted, and a composer transcript is transient and
        // derived from inputs exported elsewhere: both are erased, never exported.
        Set<String> erasedNotExported =
                Set.of(ConversationEntryRepository.COPIES_PERSON_DATA_STORE, "composer_transcript_part");
        counts.forEach((store, count) -> assertThat(
                        (long) export.path("stores").path(store).size())
                .as("Frozen preview/export parity for %s", store)
                .isEqualTo(erasedNotExported.contains(store) ? 0L : count));
        assertThat(export.path("stores").path("conversation_entry"))
                .allSatisfy(
                        row -> assertThat(row.path("text").asString()).isIn("Delivered guidance", "Retained question"));
        assertThat(export.path("stores").path("conversation_entry").toString())
                .doesNotContain("payload", "runtime-credential-canary");
        assertThat(export.toString())
                .doesNotContain(
                        "Unrelated conversation",
                        "Other person's shared reply",
                        "credential-canary",
                        "unrelated-profile-canary");
        assertThat(export.path("stores").path("workspace"))
                .singleElement()
                .satisfies(row -> assertThat(row.path("slug").asString()).isEqualTo("me--aaaaaaaaaaaaaaaa"));
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
            assertThat(conversation.refusal(derived.workspaceId(), targetAccount.getId()))
                    .contains(ConversationRefusal.PERSON_ERASED);
            assertThat(conversation.refusal(derived.workspaceId(), otherAccount.getId()))
                    .isNotEqualTo(Optional.of(ConversationRefusal.PERSON_ERASED));
        }
        for (long threadId : sharedThreads) {
            long workspaceId = Objects.requireNonNull(
                    jdbc.queryForObject("SELECT workspace_id FROM slack_thread WHERE id=?", Long.class, threadId));
            assertThat(suppression.isSourceSuppressed(workspaceId, "chat.thread", threadId))
                    .isTrue();
            assertThat(suppression.isSourceSuppressed(-1L, "chat.thread", threadId))
                    .isFalse();
        }
        var mixed = otherDerived.getFirst();
        var ownerJdbc = PostgreSQLTestContainer.ownerJdbc();
        ownerJdbc.execute("""
                CREATE FUNCTION test_hold_provider_answer() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RETURN NULL; END $$;
                CREATE TRIGGER test_hold_provider_answer BEFORE UPDATE OF redacted_at ON conversation_entry
                   FOR EACH ROW WHEN (NEW.id='%s'::uuid AND NEW.redacted_at IS NOT NULL)
                   EXECUTE FUNCTION test_hold_provider_answer();
                """.formatted(mixed.messageId()));
        try {
            personData.run(requestId);
            assertThat(conversationEntryRepository.findById(ownOutlineCopy)).isEmpty();
            assertThat(conversationEntryRepository
                            .findById(mixed.messageId())
                            .orElseThrow()
                            .isRedacted())
                    .isFalse();
            assertThat(personData.get(requestId).request().getState()).isEqualTo(PersonDataRequest.State.FAILED);
        } finally {
            ownerJdbc.execute("DROP TRIGGER test_hold_provider_answer ON conversation_entry;"
                    + " DROP FUNCTION test_hold_provider_answer();");
        }

        personData.requestErasure(requestId, administratorId, false);
        // The erasure waits for no server; the server that holds the folders removes them in its next pass.
        personData.run(requestId);
        assertThat(conversationEntryRepository
                        .findById(mixed.toolResultId())
                        .orElseThrow()
                        .isRedacted())
                .isTrue();
        assertThat(conversationEntryRepository
                        .findById(mixed.messageId())
                        .orElseThrow()
                        .isRedacted())
                .isTrue();
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
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace WHERE id = ?", Long.class, personalSpace))
                .isZero();
        assertThat(receipt.getScopeJson()).isNull();
        assertThat(receipt.getSelectionsJson()).isNull();
        Map<String, Long> completed = PersonDataStoreReceipt.counts(mapper, receipt.getCompletedJson());
        Map<String, Long> expectedReceipts = new TreeMap<>(counts);
        // Retained controls report no removal. Earlier owner steps already cleared the decision and resolver, and
        // the copies' erasure already deleted the recorded attempt inputs. A copied identity goes last, and the erasure
        // of its review item already removed it. The result step's cascade already removed the advice sources.
        for (String store : List.of(
                "review_item_copied_identity",
                "practice_advice_source",
                "identity_restriction",
                "privacy_journal",
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
        assertThat(jdbc.queryForObject(
                        "SELECT kind FROM privacy_journal WHERE entry_id=?", String.class, recoveryEntry))
                .isEqualTo("MEMBERSHIP_ENDED");
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
                        "SELECT installation_id FROM github_installation_request WHERE installation_id BETWEEN 991001"
                                + " AND 991004 ORDER BY installation_id",
                        Long.class))
                .as("the target's unadopted installation goes; the adopted one and another person's stay")
                .containsExactly(991002L, 991003L, 991004L);
        assertThat(jdbc.queryForObject(
                        "SELECT decided_by_account_id IS NULL AND status = 'DECLINED' FROM github_installation_request"
                                + " WHERE installation_id = 991003",
                        Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT account_login IS NULL AND account_id IS NULL AND status = 'ADOPTED'"
                                + " FROM github_installation_request WHERE installation_id = 991002",
                        Boolean.class))
                .as("the target's adopted installation keeps only what stops a replay")
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
            assertThat(conversation.refusal(derived.workspaceId(), targetAccount.getId()))
                    .contains(ConversationRefusal.PERSON_ERASED);
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
            ConversationEntry erased =
                    conversationEntryRepository.findById(copy.messageId()).orElseThrow();
            assertThat(erased.isRedacted()).isTrue();
            assertThat(erased.getText()).isEmpty();
            assertThat(String.valueOf(erased.getPayload())).doesNotContain("Target guidance");
            assertThat(conversationEntryRepository
                            .findById(copy.replyId())
                            .orElseThrow()
                            .getText())
                    .isEqualTo("Other reply to erased feedback");
        }
        for (var contributor : registry.stores()) {
            // Only the Hephaestus messages that copied the erased person's feedback change in another conversation.
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
            keepWorkPlaceHandoff(derived);
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
        keepWorkPlaceHandoff(unrelated);
        var preview = personData.preview(
                administratorId,
                null,
                List.of(new PersonIdentity(Objects.requireNonNull(provider.getId()), "42", null)));
        UUID requestId = preview.request().getId();
        var scope = mapper.readValue(Objects.requireNonNull(preview.request().getScopeJson()), PersonScope.class);
        assertThat(scope.derivedJobIds()).containsExactlyInAnyOrderElementsOf(copiedJobs);
        var export = PersonDataBundles.of(
                personData, Objects.requireNonNull(transactions.getTransactionManager()), requestId);
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
        assertThat(counts.get("work_place_handoff")).isEqualTo(2L);
        assertThat(counts.get("result")).isEqualTo(2L);
        assertThat(counts.get("feedback")).isEqualTo(2L);
        assertThat(export.path("stores").path("review_composition_basis").toString())
                .doesNotContain("ORIGINAL-BASIS-CANARY");
        assertThat(export.path("stores").path("work_place_handoff")).hasSize(2);
        assertThat(export.path("stores").path("work_place_handoff").toString())
                .doesNotContain("WORK-PLACE-HANDOFF-CANARY");
        assertThat(export.path("stores").path("review_job"))
                .extracting(row -> row.path("id").asString())
                .containsExactlyInAnyOrderElementsOf(
                        copiedJobs.stream().map(UUID::toString).toList());
        assertThat(export.path("stores").path("review_job"))
                .allSatisfy(row -> assertThat(row.has("capture")).isFalse());
        assertThat(export.path("stores").path(ConversationEntryRepository.COPIES_PERSON_DATA_STORE))
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
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM work_place_handoff WHERE job_id=?", Integer.class, derived.jobId()))
                    .isZero();
            assertThat(practiceResultRepository.findById(
                            derived.practiceResultIds().getFirst()))
                    .isEmpty();
            assertThat(practiceResultRepository.findById(
                            derived.practiceResultIds().getLast()))
                    .isPresent();
            assertThat(feedbackRepository.findById(derived.preparedId())).isEmpty();
            assertThat(feedbackRepository.findById(derived.deliveredId())).isPresent();
            var answer =
                    conversationEntryRepository.findById(derived.messageId()).orElseThrow();
            assertThat(answer.isRedacted()).isTrue();
            assertThat(answer.getText()).isEmpty();
            assertThat(agentJobRepository.findById(derived.jobId())).isEmpty();
            assertThat(conversationEntryRepository
                            .findById(derived.toolResultId())
                            .orElseThrow()
                            .isRedacted())
                    .isTrue();
        }
        assertThat(conversationEntryRepository
                        .findById(laterToolResult)
                        .orElseThrow()
                        .isRedacted())
                .isTrue();
        assertThat(folderResults)
                .allSatisfy(result -> assertThat(conversationEntryRepository
                                .findById(result)
                                .orElseThrow()
                                .isRedacted())
                        .as("a context folder result is erased with the person")
                        .isTrue());
        // A safe tool result stays; its answer could still have used the earlier copied context.
        var answer = conversationEntryRepository.findById(linkedAnswer).orElseThrow();
        assertThat(answer.isRedacted()).isTrue();
        assertThat(jdbc.queryForList(
                        "SELECT id FROM conversation_entry WHERE workspace_id=? AND turn_id=? AND kind='TOOL_RESULT'"
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
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM work_place_handoff WHERE job_id=?", Integer.class, unrelated.jobId()))
                .isEqualTo(1);
        assertThat(conversationEntryRepository
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
        var target = users.saveAndFlush(TestUserFactory.createUser(42L, "ask-usage-erasure", provider));
        long accountId = Objects.requireNonNull(accountFor(target).getId());
        var administrator = new Account("Administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        administrator = accounts.saveAndFlush(administrator);
        long administratorId = Objects.requireNonNull(administrator.getId());
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("ask-usage-erasure"));
        long workspaceId = Objects.requireNonNull(workspace.getId());
        membership(workspace, target);
        var conversation = conversations.findOrCreateMain(workspaceId, accountId);
        conversationTurns.submit(
                workspaceId,
                conversation.getId(),
                new ConversationTurnService.Question(
                        ConversationTurn.Surface.APP,
                        "How did my review go?",
                        UUID.randomUUID().toString(),
                        null,
                        null));
        ConversationTurn turn = claims.claimNext().orElseThrow();
        var llm = LlmCatalogTestFixtures.admittedConversationConfig();
        var admitted = ConversationTurnAdmission.of(llm, Objects.requireNonNull(llm.priceSnapshot()));

        // The preview freezes the claimed turn before any call of it has a row.
        long providerId = Objects.requireNonNull(provider.getId());
        var preview = personData.preview(administratorId, null, List.of(new PersonIdentity(providerId, "42", null)));
        UUID requestId = preview.request().getId();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM llm_usage_event WHERE source_id=?", Long.class, turn.getId()))
                .isZero();

        // The admission commits before the erasure suppresses the account; one call ends and one is still out.
        transactions.executeWithoutResult(tx -> conversationTurns.admit(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getRunId()),
                admitted.toJson()));
        UUID ended = openCall(
                workspaceId, turn.getRunId(), UsageSourceType.CONVERSATION_TURN, turn.getId(), null, admitted.price());
        completeCall(workspaceId, ended, 100_000, 0);
        UUID stillOut = openCall(
                workspaceId, turn.getRunId(), UsageSourceType.CONVERSATION_TURN, turn.getId(), null, admitted.price());

        personData.requestErasure(requestId, administratorId, false);
        personData.run(requestId);
        assertThat(personData.get(requestId).request().getState()).isEqualTo(PersonDataRequest.State.COMPLETE);

        // The turn's deletion settled the call still out; both rows keep their spend, detached together.
        String financial = "SELECT id, source_id, review_id, run_id, input_tokens, cost_usd, measurement_verification"
                + " FROM llm_usage_event WHERE workspace_id=? AND source_type='CONVERSATION_TURN' ORDER BY input_tokens"
                + " DESC";
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
            EvidenceReceipts receipts,
            EvidenceCaptures captures,
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
                receipts,
                captures,
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

    /** The export of another person's rows without the Hephaestus messages that the erasure redacts. */
    private static @Nullable Object withoutErasedCopies(
            String store, @Nullable Object rows, List<ConversationFeedbackCopy> copies, List<UUID> derivedAnswers) {
        if (!store.equals("conversation_entry") || !(rows instanceof List<?> list)) return rows;
        var copiedIds = Stream.concat(copies.stream().map(ConversationFeedbackCopy::messageId), derivedAnswers.stream())
                .map(UUID::toString)
                .collect(Collectors.toSet());
        return list.stream()
                .filter(row -> !(row instanceof JsonNode node
                        && copiedIds.contains(node.path("id").asString())))
                .toList();
    }

    /**
     * A Hephaestus message in another person's conversation that carries the target's delivered feedback through a
     * conversation copy, and that person's reply to it. The claim path cannot write such a copy; the store still
     * redacts it, because the feedback ledger names the message.
     */
    private ConversationFeedbackCopy seedFeedbackCopy(DerivedConversation target, DerivedConversation other) {
        ConversationEntry otherAnswer =
                conversationEntryRepository.findById(other.messageId()).orElseThrow();
        ConversationEntry copied = conversationEntries.append(
                otherAnswer.getWorkspaceId(),
                otherAnswer.getConversationId(),
                entry(
                        ConversationEntry.Kind.ANSWER,
                        "Target guidance copied into another conversation",
                        piAnswer("Target guidance copied into another conversation")));
        ConversationEntry reply = conversationEntries.append(
                otherAnswer.getWorkspaceId(),
                otherAnswer.getConversationId(),
                entry(ConversationEntry.Kind.USER, "Other reply to erased feedback", null));
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
                .conversationEntryId(copied.getId())
                .postedAt(Instant.now())
                .createdAt(Instant.now())
                .build());
        return new ConversationFeedbackCopy(copied.getId(), reply.getId());
    }

    private static ConversationEntryDraft entry(ConversationEntry.Kind kind, String text, @Nullable JsonNode payload) {
        return new ConversationEntryDraft(kind, ConversationEntry.Surface.APP, text, payload, null, null, null);
    }

    /** Hephaestus's answer as the end of its turn stores it, with a tool call whose arguments hold a credential. */
    private JsonNode piAnswer(String text) {
        return ConversationLedgerFixtures.toolCall(
                ConversationLedgerFixtures.piId(),
                "context-call",
                "fetch_context",
                mapper.createObjectNode().put("authorization", "credential-canary " + text));
    }

    private void recordMixedContext(DerivedConversation derived) {
        long workspaceId = derived.workspaceId();
        var answer = conversationEntryRepository.findById(derived.messageId()).orElseThrow();
        var submitted = conversationTurns.submit(
                workspaceId,
                answer.getConversationId(),
                new ConversationTurnService.Question(
                        ConversationTurn.Surface.APP,
                        "Load context",
                        UUID.randomUUID().toString(),
                        null,
                        null));
        var turn = claims.claimNext().orElseThrow();
        assertThat(turn.getId()).isEqualTo(submitted.turn().getId());
        UUID runId = Objects.requireNonNull(turn.getRunId());
        turnContext.recordOutlineContext(
                workspaceId,
                turn.getId(),
                turn.getAttempt(),
                runId,
                List.of(new OutlinePersonCopyScope.Source("https://unrelated-outline.example.test", "other-team")));
        conversationTurns.end(
                workspaceId,
                turn.getId(),
                turn.getAttempt(),
                runId,
                new ConversationTurnService.Ending(ConversationTurn.Status.DONE, List.of(), null, null));
        assertThat(jdbc.update(
                        "UPDATE conversation_entry SET turn_id=? WHERE workspace_id=? AND id IN (?,?)",
                        turn.getId(),
                        workspaceId,
                        derived.toolResultId(),
                        derived.messageId()))
                .isEqualTo(2);
    }

    private Conversation conversationOf(Workspace workspace, User owner) {
        return conversations.findOrCreateMain(
                workspace.getId(), Objects.requireNonNull(accountFor(owner).getId()));
    }

    /** A tool result in the owner's conversation that read data without recording whose. */
    private UUID seedToolResult(Workspace workspace, User owner) {
        return seedToolResult(workspace, owner, "fetch_context");
    }

    private UUID seedToolResult(Workspace workspace, User owner, String tool) {
        return conversationEntries
                .append(
                        workspace.getId(),
                        conversationOf(workspace, owner).getId(),
                        entry(
                                ConversationEntry.Kind.TOOL_RESULT,
                                "runtime-credential-canary unrelated-profile-canary",
                                ConversationLedgerFixtures.toolResult(
                                        ConversationLedgerFixtures.piId(),
                                        "context-call",
                                        tool,
                                        "runtime-credential-canary unrelated-profile-canary",
                                        false)))
                .getId();
    }

    /**
     * A Hephaestus message in the owner's conversation that showed prepared feedback, with its successful
     * {@code show_feedback} result, which copies no one's data.
     */
    private UUID seedFeedbackAnswer(Workspace workspace, User owner, UUID feedbackId) {
        Conversation conversation = conversationOf(workspace, owner);
        UUID turnId = conversationTurns
                .submit(
                        workspace.getId(),
                        conversation.getId(),
                        new ConversationTurnService.Question(
                                ConversationTurn.Surface.APP,
                                "What did my review say?",
                                UUID.randomUUID().toString(),
                                null,
                                null))
                .turn()
                .getId();
        ConversationEntry answer = conversationEntries.append(
                workspace.getId(),
                conversation.getId(),
                new ConversationEntryDraft(
                        ConversationEntry.Kind.ANSWER,
                        ConversationEntry.Surface.APP,
                        "",
                        ConversationLedgerFixtures.toolCall(
                                ConversationLedgerFixtures.piId(),
                                "show-call",
                                "show_feedback",
                                mapper.createObjectNode()
                                        .put("feedbackId", feedbackId.toString())
                                        .put("text", "Shown guidance")),
                        turnId,
                        null,
                        null));
        conversationEntries.append(
                workspace.getId(),
                conversation.getId(),
                new ConversationEntryDraft(
                        ConversationEntry.Kind.TOOL_RESULT,
                        ConversationEntry.Surface.APP,
                        "",
                        ConversationLedgerFixtures.toolResult(
                                ConversationLedgerFixtures.piId(),
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
        ConversationEntry message = conversationEntries.append(
                workspace.getId(),
                conversationOf(workspace, owner).getId(),
                entry(ConversationEntry.Kind.ANSWER, "Delivered guidance", piAnswer("Delivered guidance")));

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
                .conversationEntryId(message.getId())
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
