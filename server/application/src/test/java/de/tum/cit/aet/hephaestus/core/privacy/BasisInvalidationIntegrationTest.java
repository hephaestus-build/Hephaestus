package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.conversation.Conversation;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerFixtures;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.core.privacy.spi.BasisInvalidation;
import de.tum.cit.aet.hephaestus.core.privacy.spi.BasisInvalidation.Ref;
import de.tum.cit.aet.hephaestus.core.privacy.spi.BasisKind;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceLifecycleService;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A derived record cites conversation entries, and the removal of an entry makes it stale (ADR 0128). */
class BasisInvalidationIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private BasisInvalidation basis;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private WorkspaceLifecycleService workspaceLifecycle;

    @Autowired
    private ConversationEntryService entries;

    @Autowired
    private ConversationTurnService turns;

    @Autowired
    private OwnerScope ownerScope;

    @Autowired
    private PersonDataRegistry registry;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** Reads every person's rows, as the owner functions do. */
    private final JdbcTemplate owner = PostgreSQLTestContainer.ownerJdbc();

    private Workspace workspace;
    private Conversation main;
    private long developer;

    @BeforeEach
    void setUpConversation() {
        User user = persistUser("basis-developer");
        workspace = createWorkspace("basis", "Basis", "basis-org", AccountType.ORG, user);
        developer = accountId(user);
        main = conversations.findOrCreateMain(workspace.getId(), developer);
    }

    @Test
    void shouldMakeTheAnswerBasisStaleOnTheNextReadWhenTheDeveloperDeletesASourceMessage() {
        Ref question = entry(ask("Who reviews the API?"));
        Ref answer = entry(answer("Alex reviews it."));
        Ref other = entry(answer("Sam merges it."));
        asOwner(developer, () -> {
            basis.cite(workspace.getId(), answer, List.of(question));
            basis.cite(workspace.getId(), other, List.of(entry(ask("Who merges?"))));
        });
        assertThat(stale(developer, answer, other)).isEmpty();

        entries.deleteMessage(workspace.getId(), question.id());

        assertThat(stale(developer, answer, other)).containsExactly(answer);
    }

    @Test
    void shouldWriteADeletedSourceTombstoneWhenTheDeveloperDeletesASourceMessage() {
        UUID question = ask("Words to take back").getId();

        entries.deleteMessage(workspace.getId(), question);

        assertThat(jdbc.queryForObject(
                        "SELECT kind FROM source_tombstone WHERE workspace_id = ? AND source_kind = 'ENTRY'"
                                + " AND source_id = ?",
                        String.class,
                        workspace.getId(),
                        question))
                .isEqualTo("DELETED_SOURCE");
    }

    @Test
    void shouldMakeARecordStaleWhenARecordThatItCitesIsStale() {
        Ref question = entry(ask("What did we decide?"));
        Ref answer = entry(answer("Deploy on Monday."));
        Ref recap = entry(answer("Last week: deploy on Monday."));
        asOwner(developer, () -> {
            basis.cite(workspace.getId(), answer, List.of(question));
            basis.cite(workspace.getId(), recap, List.of(answer));
        });

        entries.deleteMessage(workspace.getId(), question.id());

        assertThat(stale(developer, recap)).containsExactly(recap);
    }

    @Test
    void shouldShowAnotherPersonNoReferenceWhenTheyReadTheDevelopersBasis() {
        Ref question = entry(ask("Who reviews?"));
        Ref answer = entry(answer("Alex."));
        asOwner(developer, () -> {
            basis.cite(workspace.getId(), answer, List.of(question));
        });
        entries.deleteMessage(workspace.getId(), question.id());
        long colleague = Objects.requireNonNull(persistAccount("Colleague").getId());

        assertThat(stale(colleague, answer)).isEmpty();
        assertThat(read(
                        colleague,
                        () -> Objects.requireNonNull(jdbc.queryForObject(
                                "SELECT count(*) FROM basis_edge WHERE workspace_id = ?",
                                Long.class,
                                workspace.getId()))))
                .isZero();
    }

    @Test
    void shouldRefuseACitationWhenTheTransactionBindsNoOwner() {
        Ref question = entry(ask("Who reviews?"));
        Ref answer = entry(answer("Alex."));

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                        .executeWithoutResult(status -> basis.cite(workspace.getId(), answer, List.of(question))))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("row-level security policy for table \"basis_edge\"");
    }

    @Test
    void shouldDeleteTheReferencesAndTombstonesOfAWorkspaceWhenItsConversationsArePurged() {
        Ref question = entry(ask("Who reviews?"));
        Ref answer = entry(answer("Alex."));
        asOwner(developer, () -> {
            basis.cite(workspace.getId(), answer, List.of(question));
        });
        entries.deleteMessage(workspace.getId(), question.id());

        // Through the lifecycle, which admits the cross-owner sweep (#5142) that fires the delete triggers.
        workspaceLifecycle.purgeWorkspace(workspace.getWorkspaceSlug());

        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM basis_edge WHERE workspace_id = ?", Long.class, workspace.getId()))
                .isZero();
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM source_tombstone WHERE workspace_id = ?", Long.class, workspace.getId()))
                .isZero();
    }

    @Test
    void shouldMoveTheReferencesWithTheirEntryWhenTheConversationChangesOwner() {
        Ref question = entry(ask("Who reviews?"));
        Ref answer = entry(answer("Alex."));
        asOwner(developer, () -> {
            basis.cite(workspace.getId(), answer, List.of(question));
        });
        long claimant = Objects.requireNonNull(persistAccount("Claimant").getId());

        // An absorbed account's conversations change owner as the owner function changes them.
        owner.update("UPDATE conversation SET account_id = ? WHERE id = ?", claimant, main.getId());

        assertThat(owner.queryForObject(
                        "SELECT account_id FROM basis_edge WHERE dependent_id = ?", Long.class, answer.id()))
                .isEqualTo(claimant);
    }

    @Test
    void shouldDeleteTheReferencesOfThePersonWhenTheirDataIsErased() {
        Ref question = entry(ask("Who reviews?"));
        Ref answer = entry(answer("Alex."));
        asOwner(developer, () -> {
            basis.cite(workspace.getId(), answer, List.of(question));
        });
        PersonDataContributor store = registry.stores().stream()
                .filter(candidate -> candidate.store().equals("basis_edge"))
                .findFirst()
                .orElseThrow();
        var scope = new PersonScope(developer, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        asOwner(developer, () -> store.erase(store.select(scope), scope));

        assertThat(owner.queryForObject("SELECT count(*) FROM basis_edge WHERE account_id = ?", Long.class, developer))
                .isZero();
    }

    private ConversationEntry ask(String text) {
        ConversationTurnService.Submission asked = turns.submit(
                workspace.getId(),
                main.getId(),
                new ConversationTurnService.Question(
                        ConversationTurn.Surface.APP, text, UUID.randomUUID().toString(), null, null));
        return asked.question();
    }

    private ConversationEntry answer(String text) {
        return entries.append(workspace.getId(), main.getId(), ConversationLedgerFixtures.hephaestus(text));
    }

    private static Ref entry(ConversationEntry entry) {
        return new Ref(BasisKind.ENTRY, entry.getId());
    }

    private Set<Ref> stale(long account, Ref... dependents) {
        Collection<Ref> refs = List.of(dependents);
        return read(account, () -> basis.stale(workspace.getId(), refs));
    }

    private void asOwner(long account, Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ownerScope.ofJob(account);
            work.run();
        });
    }

    private <T> T read(long account, Supplier<T> work) {
        return Objects.requireNonNull(new TransactionTemplate(transactionManager).execute(status -> {
            ownerScope.ofJob(account);
            return work.get();
        }));
    }
}
