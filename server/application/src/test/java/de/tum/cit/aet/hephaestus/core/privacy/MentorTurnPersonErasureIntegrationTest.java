package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryService;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerFixtures;
import de.tum.cit.aet.hephaestus.mentor.MentorRejectedException;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.mentor.adapter.MentorPersonDataCatalog;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * A person erasure and the Heph turns of another member that run while it fails and resumes: what {@code fetch_context}
 * copied of the person before the last step must not stay.
 */
class MentorTurnPersonErasureIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    private static final String WORKER = "worker-erasure";

    @Autowired
    private JdbcTemplate jdbc;

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
    private MentorPersonDataCatalog mentorCatalog;

    @Autowired
    private PersonDataRegistry registry;

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private MentorTurnService turns;

    @Autowired
    private MentorEntryService entryService;

    @Autowired
    private PersonDataService personData;

    /** The developer's workspace and conversation, and the erasure of another member that the test runs. */
    private record Erasure(
            Workspace workspace, MentorConversation main, PersonDataService service, UUID requestId, long adminId) {}

    /** Requests and runs the erasure of the target, whose data a turn's {@code fetch_context} copies. */
    private Erasure requestErasure(PersonDataContributor source) {
        databaseTestUtils.cleanDatabase();
        var provider = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://heph-erasure.example.test"));
        User target = users.saveAndFlush(TestUserFactory.createUser(42L, "target", provider));
        User developer = users.saveAndFlush(TestUserFactory.createUser(84L, "developer", provider));
        Workspace workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("heph-erasure"));
        membership(workspace, target);
        membership(workspace, developer);
        MentorConversation main = conversations.findOrCreateMain(workspace.getId(), accountFor(developer));
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

    private MentorEntry toolResultOf(Workspace workspace, MentorTurn turn) {
        List<MentorEntry> stored = MentorLedgerFixtures.turnEntries(entityManager, workspace.getId(), turn.getId());
        assertThat(stored.getLast().isRedacted()).as("the answer stays").isFalse();
        return stored.stream()
                .filter(entry -> entry.getKind() == MentorEntry.Kind.TOOL_RESULT)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void shouldEraseTheToolResultsOfTurnsThatRanWhileTheErasureFailedAndResumed() {
        assertThat(registry.stores().getLast().store()).isEqualTo(MentorEntryRepository.COPIES_PERSON_DATA_STORE);
        Erasure erasure = requestErasure(sourceThatFailsOnce());
        assertThat(requests.findById(erasure.requestId()).orElseThrow().getState())
                .isEqualTo(PersonDataRequest.State.FAILED);
        MentorTurn whileFailed = ask(erasure.workspace(), erasure.main(), "Who reviews the API?");
        end(whileFailed, "Alex does.");
        MentorTurn acrossResume = ask(erasure.workspace(), erasure.main(), "And who merges?");
        run(erasure);
        end(acrossResume, "Alex merges.");

        assertThat(requests.findById(erasure.requestId()).orElseThrow().getState())
                .isEqualTo(PersonDataRequest.State.COMPLETE);
        for (MentorTurn turn : List.of(whileFailed, acrossResume)) {
            assertThat(toolResultOf(erasure.workspace(), turn)).satisfies(result -> {
                assertThat(result.isRedacted()).isTrue();
                assertThat(String.valueOf(result.getPayload())).doesNotContain("Target's open review");
            });
        }
    }

    @Test
    void shouldKeepTheToolResultsOfATurnThatStartedAfterTheErasureCompleted() {
        Erasure erasure = requestErasure(sourceThatFailsOnce());
        run(erasure);
        assertThat(requests.findById(erasure.requestId()).orElseThrow().getState())
                .isEqualTo(PersonDataRequest.State.COMPLETE);

        MentorTurn later = ask(erasure.workspace(), erasure.main(), "Who reviews the API?");
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
        Workspace workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("heph-erasure"));
        membership(workspace, developer);
        MentorConversation main = conversations.findOrCreateMain(workspace.getId(), developerId);
        MentorEntry parent = entryService.append(workspace.getId(), main.getId(), MentorLedgerFixtures.heph("Hello"));
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
                new MentorTurnService.Question(
                        MentorTurn.Surface.APP,
                        "Late question",
                        UUID.randomUUID().toString(),
                        null,
                        null)));
        assertErased(() -> conversations.openThread(workspace.getId(), parent.getId()));
        personData.run(requestId);
        assertThat(requests.findById(requestId).orElseThrow().getState()).isEqualTo(PersonDataRequest.State.COMPLETE);
        assertErased(() -> conversations.findOrCreateMain(workspace.getId(), developerId));

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM mentor_conversation WHERE account_id = ?", Long.class, developerId))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM mentor_entry WHERE workspace_id = ?", Long.class, workspace.getId()))
                .isZero();
    }

    private static void assertErased(ThrowingCallable write) {
        assertThatThrownBy(write)
                .isInstanceOfSatisfying(
                        MentorRejectedException.class,
                        rejected ->
                                assertThat(rejected.reason()).isEqualTo(MentorRejectedException.Reason.PERSON_ERASED));
    }

    private MentorTurn ask(Workspace workspace, MentorConversation main, String text) {
        turns.submit(
                workspace.getId(),
                main.getId(),
                new MentorTurnService.Question(
                        MentorTurn.Surface.APP, text, UUID.randomUUID().toString(), null, null));
        return turns.claimNext(WORKER, Duration.ofMinutes(5)).orElseThrow();
    }

    /** Ends the turn as a host does after a {@code fetch_context} read that copied the person's data. */
    private void end(MentorTurn turn, String answer) {
        List<JsonNode> appended = List.of(
                MentorLedgerFixtures.toolCall(
                        MentorLedgerFixtures.piId(),
                        "fetch-1",
                        "fetch_context",
                        JsonNodeFactory.instance.objectNode().put("path", "review_requests.json")),
                MentorLedgerFixtures.toolResult(
                        MentorLedgerFixtures.piId(), "fetch-1", "fetch_context", "Target's open review", false),
                MentorLedgerFixtures.answer(MentorLedgerFixtures.piId(), answer));
        turns.end(
                turn.getId(),
                turn.getAttempt(),
                WORKER,
                new MentorTurnService.AttemptEnd(MentorTurn.Status.DONE, appended, null, null));
    }

    /** A source step that fails on its first run, before the last step, so the erasure fails and resumes. */
    private PersonDataContributor sourceThatFailsOnce() {
        var failOnce = new AtomicBoolean(true);
        return new JdbcPersonDataStore(
                namedJdbc, mapper, "source_step", "user", "t.id = ANY(:users)", "id,name", "id", "name=NULL", 0) {
            @Override
            public long erase(PersonDataSelection selection, PersonScope frozenScope) {
                long count = super.erase(selection, frozenScope);
                if (failOnce.getAndSet(false)) {
                    throw new IllegalStateException("source step failed");
                }
                return count;
            }
        };
    }

    private PersonDataService serviceWith(PersonDataContributor source) {
        PersonDataContributor toolResults = mentorCatalog.contributors().stream()
                .filter(store -> store.store().equals(MentorEntryRepository.COPIES_PERSON_DATA_STORE))
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
                jdbc);
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

    @PersonDataStores({"source_step", MentorEntryRepository.COPIES_PERSON_DATA_STORE})
    private record ErasureSteps(List<PersonDataContributor> contributors) implements PersonDataCatalog {}
}
