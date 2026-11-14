package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.conversation.Conversation;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerFixtures;
import de.tum.cit.aet.hephaestus.conversation.ConversationRejectedException;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnClaims;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.conversation.adapter.ConversationPersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataStores;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * A person erasure and the Hephaestus turns of another member that run while it fails and resumes: what {@code fetch_context}
 * copied of the person before the last step must not stay.
 */
class ConversationTurnPersonErasureIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ConversationTurnClaims claims;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private NamedParameterJdbcTemplate namedJdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private PersonIdentityResolver resolver;

    @Autowired
    private PersonDataRequestRepository requests;

    @Autowired
    private IdentityRestrictionService suppression;

    @Autowired
    private PersonDataWriteFence writeFence;

    @Autowired
    private PersonDataCopyFence copyFence;

    @Autowired
    private IssuedJwtRepository tokens;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private UserRepository users;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private IdentityRepository links;

    @Autowired
    private MembershipRepository memberships;

    @Autowired
    private ConversationPersonDataCatalog conversationCatalog;

    @Autowired
    private PersonDataRegistry registry;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ConversationTurnService turns;

    @Autowired
    private ConversationEntryService entryService;

    @Autowired
    private PersonDataService personData;

    /** The developer's workspace and conversation, and the erasure of another member that the test runs. */
    private record Erasure(
            Workspace workspace, Conversation main, PersonDataService service, UUID requestId, long adminId) {}

    /** Requests and runs the erasure of the target, whose data a turn's {@code fetch_context} copies. */
    private Erasure requestErasure(PersonDataContributor source) {
        databaseTestUtils.cleanDatabase();
        var provider = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://heph-erasure.example.test"));
        User target = users.saveAndFlush(TestUserFactory.createUser(42L, "target", provider));
        User developer = users.saveAndFlush(TestUserFactory.createUser(84L, "developer", provider));
        Workspace workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("ask-erasure"));
        membership(workspace, target);
        membership(workspace, developer);
        Conversation main = conversations.findOrCreateMain(workspace.getId(), accountFor(developer));
        var administrator = new Account("Administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        long adminId =
                Objects.requireNonNull(accounts.saveAndFlush(administrator).getId());
        PersonDataService service = serviceWith(source);
        var identities = List.of(new PersonIdentity(Objects.requireNonNull(provider.getId()), "42", null));
        UUID requestId = Objects.requireNonNull(
                        transaction().execute(status -> service.preview(adminId, null, identities)))
                .request()
                .getId();
        var erasure = new Erasure(workspace, main, service, requestId, adminId);
        run(erasure);
        return erasure;
    }

    /** Confirms the erasure (again, after a failure) and runs it. */
    private void run(Erasure erasure) {
        transaction()
                .executeWithoutResult(
                        status -> erasure.service().requestErasure(erasure.requestId(), erasure.adminId(), true));
        erasure.service().run(erasure.requestId());
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactions);
    }

    private ConversationEntry toolResultOf(Workspace workspace, ConversationTurn turn) {
        List<ConversationEntry> stored =
                ConversationLedgerFixtures.turnEntries(entityManager, workspace.getId(), turn.getId());
        return stored.stream()
                .filter(entry -> entry.getKind() == ConversationEntry.Kind.TOOL_RESULT)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void shouldEraseTheToolResultsOfTurnsThatRanWhileTheErasureFailedAndResumed() {
        assertThat(registry.stores().getLast().store()).isEqualTo(ConversationEntryRepository.COPIES_PERSON_DATA_STORE);
        Erasure erasure = requestErasure(sourceThatFailsOnce());
        assertThat(requests.findById(erasure.requestId()).orElseThrow().getState())
                .isEqualTo(PersonDataRequest.State.FAILED);
        ConversationTurn whileFailed = ask(erasure.workspace(), erasure.main(), "Who reviews the API?");
        end(whileFailed, "Alex does.");
        ConversationTurn acrossResume = ask(erasure.workspace(), erasure.main(), "And who merges?");
        run(erasure);
        end(acrossResume, "Alex merges.");

        assertThat(requests.findById(erasure.requestId()).orElseThrow().getState())
                .isEqualTo(PersonDataRequest.State.COMPLETE);
        for (ConversationTurn turn : List.of(whileFailed, acrossResume)) {
            assertThat(toolResultOf(erasure.workspace(), turn)).satisfies(result -> {
                assertThat(result.isRedacted()).isTrue();
                assertThat(String.valueOf(result.getPayload())).doesNotContain("Target's open review");
            });
            assertThat(ConversationLedgerFixtures.turnEntries(
                            entityManager, erasure.workspace().getId(), turn.getId()))
                    .filteredOn(entry -> entry.getKind() == ConversationEntry.Kind.ANSWER)
                    .allSatisfy(entry -> {
                        assertThat(entry.isRedacted()).isTrue();
                        assertThat(entry.getText()).isEmpty();
                        assertThat(String.valueOf(entry.getPayload())).doesNotContain("Alex");
                    });
        }
    }

    @Test
    void shouldEraseAnAnswerThatUsesEarlierContextWithoutAnotherToolCall() {
        Erasure erasure = requestErasure(sourceThatFailsOnce());
        ConversationTurn earlier = ask(erasure.workspace(), erasure.main(), "Who reviews?");
        end(earlier, "Alex reviews.");
        ConversationTurn running = ask(erasure.workspace(), erasure.main(), "And who merges?");
        run(erasure);
        turns.end(
                running.getWorkspaceId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                new ConversationTurnService.Ending(
                        ConversationTurn.Status.DONE,
                        List.of(ConversationLedgerFixtures.answer(ConversationLedgerFixtures.piId(), "Alex merges.")),
                        null,
                        null));
        assertThat(turns.require(running.getWorkspaceId(), running.getId()).getStatus())
                .isEqualTo(ConversationTurn.Status.FAILED);
        assertThat(ConversationLedgerFixtures.turnEntries(
                        entityManager, erasure.workspace().getId(), running.getId()))
                .filteredOn(entry -> entry.getKind() == ConversationEntry.Kind.ANSWER)
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.isRedacted()).isTrue();
                    assertThat(entry.getText()).isEmpty();
                    assertThat(String.valueOf(entry.getPayload())).doesNotContain("Alex");
                });
    }

    @Test
    void shouldKeepTheToolResultsOfATurnThatStartedAfterTheErasureCompleted() {
        Erasure erasure = requestErasure(sourceThatFailsOnce());
        run(erasure);
        assertThat(requests.findById(erasure.requestId()).orElseThrow().getState())
                .isEqualTo(PersonDataRequest.State.COMPLETE);

        ConversationTurn later = ask(erasure.workspace(), erasure.main(), "Who reviews the API?");
        end(later, "Sam does.");

        assertThat(toolResultOf(erasure.workspace(), later)).satisfies(result -> {
            assertThat(result.isRedacted()).isFalse();
            assertThat(String.valueOf(result.getPayload())).contains("Target's open review");
        });
    }

    @Test
    void shouldStoreNoConversationOrQuestionOfADeveloperWhoseErasureCameAfterTheAdmission() {
        databaseTestUtils.cleanDatabase();
        var provider = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://heph-erasure.example.test"));
        User developer = users.saveAndFlush(TestUserFactory.createUser(84L, "developer", provider));
        long developerId = accountFor(developer);
        Workspace workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("ask-erasure"));
        membership(workspace, developer);
        Conversation main = conversations.findOrCreateMain(workspace.getId(), developerId);
        ConversationEntry parent =
                entryService.append(workspace.getId(), main.getId(), ConversationLedgerFixtures.hephaestus("Hello"));
        var administrator = new Account("Administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        long adminId =
                Objects.requireNonNull(accounts.saveAndFlush(administrator).getId());
        var identities = List.of(new PersonIdentity(Objects.requireNonNull(provider.getId()), "84", null));
        UUID requestId = Objects.requireNonNull(
                        transaction().execute(status -> personData.preview(adminId, null, identities)))
                .request()
                .getId();

        // The developer's requests passed admission before this point; their writes arrive now.
        transaction().executeWithoutResult(status -> personData.requestErasure(requestId, adminId, true));
        assertErased(() -> turns.submit(
                workspace.getId(),
                main.getId(),
                new ConversationTurnService.Question(
                        ConversationTurn.Surface.APP,
                        "Late question",
                        UUID.randomUUID().toString(),
                        null,
                        null)));
        assertErased(() -> conversations.openThread(workspace.getId(), parent.getId()));
        personData.run(requestId);
        assertThat(requests.findById(requestId).orElseThrow().getState()).isEqualTo(PersonDataRequest.State.COMPLETE);
        assertErased(() -> conversations.findOrCreateMain(workspace.getId(), developerId));

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM conversation WHERE account_id = ?", Long.class, developerId))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM conversation_entry WHERE workspace_id = ?",
                        Long.class,
                        workspace.getId()))
                .isZero();
    }

    private static void assertErased(ThrowingCallable write) {
        assertThatThrownBy(write)
                .isInstanceOfSatisfying(
                        ConversationRejectedException.class,
                        rejected -> assertThat(rejected.reason())
                                .isEqualTo(ConversationRejectedException.Reason.PERSON_ERASED));
    }

    private ConversationTurn ask(Workspace workspace, Conversation main, String text) {
        turns.submit(
                workspace.getId(),
                main.getId(),
                new ConversationTurnService.Question(
                        ConversationTurn.Surface.APP, text, UUID.randomUUID().toString(), null, null));
        return claims.claimNext().orElseThrow();
    }

    /** Ends the turn as a host does after a {@code fetch_context} read that copied the person's data. */
    private void end(ConversationTurn turn, String answer) {
        List<JsonNode> appended = List.of(
                ConversationLedgerFixtures.toolCall(
                        ConversationLedgerFixtures.piId(),
                        "fetch-1",
                        "fetch_context",
                        JsonNodeFactory.instance.objectNode().put("path", "review_requests.json")),
                ConversationLedgerFixtures.toolResult(
                        ConversationLedgerFixtures.piId(), "fetch-1", "fetch_context", "Target's open review", false),
                ConversationLedgerFixtures.answer(ConversationLedgerFixtures.piId(), answer));
        turns.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getRunId()),
                new ConversationTurnService.Ending(ConversationTurn.Status.DONE, appended, null, null));
    }

    @ParameterizedTest
    @MethodSource("temporaryFailures")
    void shouldRetryATemporaryStoreFailureFromTheScheduledBacklogWithoutAnotherAdminConfirmation(
            RuntimeException failure) {
        Erasure erasure = requestErasure(sourceThatFailsOnce(failure));
        var failed = erasure.service().get(erasure.requestId()).request();
        assertThat(failed.getState()).isEqualTo(PersonDataRequest.State.ERASING);
        assertThat(failed.getFailureCode()).isEqualTo("STORE_ERASURE_RETRY_PENDING");
        var runner = new PersonDataJobRunner(jdbc, erasure.service());
        runner.run();
        var completed = erasure.service().get(erasure.requestId()).request();
        assertThat(completed.getState()).isEqualTo(PersonDataRequest.State.COMPLETE);
        assertThat(completed.getFailureCode()).isNull();
        assertThat(completed.getScopeJson()).isNull();
    }

    static Stream<RuntimeException> temporaryFailures() {
        return Stream.of(
                new TransientDataAccessResourceException("temporary"),
                new IllegalStateException("wrapper", new TransientDataAccessResourceException("temporary")),
                new DataAccessResourceFailureException("connection unavailable"),
                new CannotCreateTransactionException("pool unavailable"));
    }

    @Test
    void shouldRetryAfterTheStoreConnectionAndItsRollbackBothFail() {
        Erasure erasure =
                requestErasure(sourceThatFails(1, () -> jdbc.execute("SELECT pg_terminate_backend(pg_backend_pid())")));
        var interrupted = erasure.service().get(erasure.requestId()).request();
        assertThat(interrupted.getState()).isEqualTo(PersonDataRequest.State.ERASING);
        assertThat(interrupted.getFailureCode()).isEqualTo("STORE_ERASURE_RETRY_PENDING");

        new PersonDataJobRunner(jdbc, erasure.service()).run();
        var completed = erasure.service().get(erasure.requestId()).request();
        assertThat(completed.getState()).isEqualTo(PersonDataRequest.State.COMPLETE);
        assertThat(completed.getFailureCode()).isNull();
    }

    @Test
    void shouldStopRepeatedStoreFailuresAndKeepTheRequestVisibleForAnAdmin() {
        double failuresBefore = meters.counter("person.data.erasure.failures").count();
        Erasure erasure =
                requestErasure(sourceThatFails(2, new DataAccessResourceFailureException("connection unavailable")));
        var runner = new PersonDataJobRunner(jdbc, erasure.service());
        runner.run();
        var stopped = erasure.service().get(erasure.requestId()).request();
        assertThat(stopped.getState()).isEqualTo(PersonDataRequest.State.FAILED);
        assertThat(stopped.getFailureCode()).isEqualTo("STORE_ERASURE_FAILED");
        assertThat(stopped.getScopeJson()).isNotNull();
        assertThat(meters.counter("person.data.erasure.failures").count()).isEqualTo(failuresBefore + 1);
        runner.run();
        assertThat(erasure.service().get(erasure.requestId()).request().getState())
                .isEqualTo(PersonDataRequest.State.FAILED);
    }

    /** A source step that fails on its first run, before the last step, so the erasure fails and resumes. */
    private PersonDataContributor sourceThatFailsOnce() {
        return sourceThatFailsOnce(new IllegalStateException("source step failed"));
    }

    private PersonDataContributor sourceThatFailsOnce(RuntimeException failure) {
        return sourceThatFails(1, failure);
    }

    private PersonDataContributor sourceThatFails(int failures, RuntimeException failure) {
        return sourceThatFails(failures, () -> {
            throw failure;
        });
    }

    private PersonDataContributor sourceThatFails(int failures, Runnable fail) {
        var remaining = new AtomicInteger(failures);
        return new JdbcPersonDataStore(
                namedJdbc, mapper, "source_step", "user", "t.id = ANY(:users)", "id,name", "id", "name=NULL", 0) {
            @Override
            public long erase(PersonDataSelection selection, PersonScope frozenScope) {
                long count = super.erase(selection, frozenScope);
                if (remaining.getAndDecrement() > 0) {
                    fail.run();
                }
                return count;
            }
        };
    }

    private PersonDataService serviceWith(PersonDataContributor source) {
        PersonDataContributor toolResults = conversationCatalog.contributors().stream()
                .filter(store -> store.store().equals(ConversationEntryRepository.COPIES_PERSON_DATA_STORE))
                .findFirst()
                .orElseThrow();
        return new PersonDataService(
                resolver,
                new PersonDataRegistry(List.of(new ErasureSteps(List.of(source, toolResults)))),
                List.of(),
                requests,
                suppression,
                writeFence,
                copyFence,
                tokens,
                transactions,
                mapper,
                jdbc,
                meters);
    }

    /** The account linked to the user's provider identity, so an erasure by that identity resolves it. */
    private long accountFor(User user) {
        var existing = links.findActiveByProviderSubject(
                Objects.requireNonNull(user.getProvider().getId()),
                user.getNativeId().toString(),
                null);
        if (existing.isPresent())
            return Objects.requireNonNull(existing.get().getAccount().getId());
        var account = accounts.saveAndFlush(new Account(user.getLogin()));
        Identity link = new Identity();
        link.setAccount(account);
        link.setProviderId(Objects.requireNonNull(user.getProvider().getId()));
        link.setProviderUserKey(user.getNativeId().toString());
        link.setTeamKey("");
        links.saveAndFlush(link);
        return Objects.requireNonNull(account.getId());
    }

    private void membership(Workspace workspace, User user) {
        Membership membership = new Membership();
        membership.setWorkspace(workspace);
        membership.setId(new Membership.Id(workspace.getId(), accountFor(user)));
        memberships.saveAndFlush(membership);
    }

    @PersonDataStores({"source_step", ConversationEntryRepository.COPIES_PERSON_DATA_STORE})
    private record ErasureSteps(List<PersonDataContributor> contributors) implements PersonDataCatalog {}
}
