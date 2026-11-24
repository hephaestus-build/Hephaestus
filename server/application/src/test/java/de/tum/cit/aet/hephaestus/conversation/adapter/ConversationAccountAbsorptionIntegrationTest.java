package de.tum.cit.aet.hephaestus.conversation.adapter;

import static de.tum.cit.aet.hephaestus.core.auth.oauth.VerifiedAccountClaimFixture.verifiedClaim;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.conversation.Conversation;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryDraft;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationLeaseLostException;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerEvents;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerFixtures;
import de.tum.cit.aet.hephaestus.conversation.ConversationPartialUpdates;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnClaims;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.core.auth.PeopleRegistryService;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.testconfig.AccountFixtures;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.SharedEventRecorder;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceLifecycleService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Absorbing an unclaimed account moves its Hephaestus conversations to the claiming account. Where both have a main
 * conversation in a workspace, the absorbed one merges into the claiming one ({@link ConversationAccountAbsorptionContributor}).
 */
@Transactional
class ConversationAccountAbsorptionIntegrationTest extends BaseIntegrationTest {

    @BeforeEach
    void clearOtherTestsRuns() {
        // Turn capacity is global, including committed runs from other test classes.
        databaseTestUtils.cleanDatabase();
    }

    @Autowired
    private ConversationTurnClaims claims;

    @Autowired
    private SharedEventRecorder events;

    @Autowired
    private PeopleRegistryService people;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private IdentityRepository identities;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private WorkspaceLifecycleService lifecycle;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ConversationEntryService entries;

    @Autowired
    private ConversationTurnService turns;

    @Autowired
    private ConversationTurnRepository turnRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    private Workspace workspace(String name) {
        return workspaces.save(WorkspaceTestFixtures.activeWorkspace(
                name + "-" + UUID.randomUUID().toString().substring(0, 8)));
    }

    private long claimedAccount(String name) {
        return account(name, Account.Status.ACTIVE, IdentityProviderType.GITHUB, "https://github.com", "");
    }

    private long unclaimedSlackAccount() {
        return account(
                "Slack developer", Account.Status.UNCLAIMED, IdentityProviderType.SLACK, "https://slack.com", "T1");
    }

    private long account(String name, Account.Status status, IdentityProviderType type, String serverUrl, String team) {
        var account = AccountFixtures.withStatus(name, status);
        account = accounts.save(account);
        var provider = providers
                .findByTypeAndServerUrl(type, serverUrl)
                .orElseGet(() -> providers.save(new IdentityProvider(type, serverUrl)));
        var identity = new Identity();
        identity.setAccount(account);
        identity.setProviderId(Objects.requireNonNull(provider.getId()));
        identity.setProviderUserKey("U" + UUID.randomUUID().toString().substring(0, 8));
        identity.setTeamKey(team);
        identities.save(identity);
        return Objects.requireNonNull(account.getId());
    }

    private PersonIdentity identityOf(long accountId) {
        var identity = identities.findActiveByAccountId(accountId).getFirst();
        return PersonIdentity.of(identity.getProviderId(), identity.getProviderUserKey(), identity.getTeamKey());
    }

    @Test
    void shouldRefuseToMoveASlackConversationWithoutItsOwnersVerifiedIdentity() {
        var workspace = workspace("absorb-wrong-identity");
        long claimed = claimedAccount("Claimed developer");
        long unclaimed = unclaimedSlackAccount();
        long otherSlackAccount = unclaimedSlackAccount();
        var conversation = conversations.findOrCreateMain(workspace.getId(), unclaimed);
        entityManager.flush();
        assertThat(jdbc.update("""
                UPDATE conversation SET slack_team_id = 'T1', slack_user_id = ?, slack_channel_id = 'D1'
                WHERE id = ?
                """, identityOf(unclaimed).subject(), conversation.getId()))
                .isOne();

        assertThatThrownBy(
                        () -> people.absorbUnclaimed(verifiedClaim(claimed, unclaimed, identityOf(otherSlackAccount))))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(jdbc.queryForMap(
                        "SELECT account_id, slack_channel_id FROM conversation WHERE id = ?", conversation.getId()))
                .containsEntry("account_id", unclaimed)
                .containsEntry("slack_channel_id", "D1");
    }

    private ConversationEntry hephaestus(Workspace workspace, Conversation conversation, String text) {
        return entries.append(workspace.getId(), conversation.getId(), ConversationLedgerFixtures.hephaestus(text));
    }

    /** The conversation's entries in order: their ids, and whether a redaction erased them. */
    private record Row(UUID id, long seq, boolean redacted) {}

    private List<Row> rows(UUID conversationId) {
        return jdbc.query(
                "SELECT id, seq, redacted_at IS NOT NULL FROM conversation_entry WHERE conversation_id = ? ORDER BY seq",
                (rs, row) ->
                        new Row(Objects.requireNonNull(rs.getObject(1, UUID.class)), rs.getLong(2), rs.getBoolean(3)),
                conversationId);
    }

    @Test
    void shouldMergeTheAbsorbedMainAfterTheClaimingMainAndMoveEveryOtherConversation() {
        Workspace shared = workspace("absorb-shared");
        Workspace onlyAbsorbed = workspace("absorb-only");
        long claimed = claimedAccount("Claimed developer");
        long unclaimed = unclaimedSlackAccount();

        Conversation kept = conversations.findOrCreateMain(shared.getId(), claimed);
        UUID keptFirst = hephaestus(shared, kept, "Kept one").getId();
        UUID keptSecond = hephaestus(shared, kept, "Kept two").getId();
        Conversation absorbed = conversations.findOrCreateMain(shared.getId(), unclaimed);
        ConversationEntry absorbedFirst = hephaestus(shared, absorbed, "Absorbed one");
        UUID summary = entries.append(
                        shared.getId(),
                        absorbed.getId(),
                        new ConversationEntryDraft(
                                ConversationEntry.Kind.SUMMARY,
                                ConversationEntry.Surface.SYSTEM,
                                "What was said before.",
                                ConversationLedgerFixtures.compaction(
                                        ConversationLedgerFixtures.piId(), "What was said.", "x"),
                                null,
                                null,
                                null))
                .getId();
        UUID absorbedLast = hephaestus(shared, absorbed, "Absorbed two").getId();
        Conversation thread = conversations.openThread(shared.getId(), absorbedFirst.getId());
        UUID inThread = hephaestus(shared, thread, "In the thread").getId();
        member(shared, unclaimed);
        ConversationTurnService.Submission asked = turns.submit(
                shared.getId(),
                absorbed.getId(),
                new ConversationTurnService.Question(
                        ConversationTurn.Surface.APP,
                        "Still there?",
                        UUID.randomUUID().toString(),
                        null,
                        null));
        Conversation elsewhere = conversations.findOrCreateMain(onlyAbsorbed.getId(), unclaimed);

        people.absorbUnclaimed(verifiedClaim(claimed, unclaimed, identityOf(unclaimed)));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM conversation WHERE id = ?", Long.class, absorbed.getId()))
                .as("the absorbed main is gone")
                .isZero();
        assertThat(rows(kept.getId()))
                .as("the absorbed entries follow the kept ones in their own order, under the same ids")
                .extracting(Row::id)
                .containsExactly(
                        keptFirst,
                        keptSecond,
                        absorbedFirst.getId(),
                        summary,
                        absorbedLast,
                        asked.question().getId());
        assertThat(rows(kept.getId())).extracting(Row::seq).isSorted().doesNotHaveDuplicates();
        assertThat(rows(kept.getId()))
                .as("only the absorbed summary is erased, so the next turn summarizes the merged conversation")
                .filteredOn(Row::redacted)
                .extracting(Row::id)
                .containsExactly(summary);
        assertThat(jdbc.queryForMap(
                        "SELECT parent_conversation_id, parent_entry_id, account_id FROM conversation WHERE id = ?",
                        thread.getId()))
                .as("the thread follows its parent message")
                .containsEntry("parent_conversation_id", kept.getId())
                .containsEntry("parent_entry_id", absorbedFirst.getId())
                .containsEntry("account_id", claimed);
        assertThat(jdbc.queryForObject(
                        "SELECT conversation_id FROM conversation_turn WHERE id = ?",
                        UUID.class,
                        asked.turn().getId()))
                .isEqualTo(kept.getId());
        assertThat(jdbc.queryForObject(
                        "SELECT r.concurrency_key FROM conversation_turn t JOIN agent_run r ON r.id = t.run_id WHERE t.id = ?",
                        String.class,
                        asked.turn().getId()))
                .as("its queued run waits behind the kept conversation's turns")
                .isEqualTo("conversation:" + kept.getId());
        assertThat(jdbc.queryForObject(
                        "SELECT account_id FROM conversation WHERE id = ?", Long.class, elsewhere.getId()))
                .as("a main without a clash moves")
                .isEqualTo(claimed);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM conversation WHERE account_id = ?", Long.class, unclaimed))
                .isZero();
        assertThat(jdbc.queryForList(
                        "SELECT account_id FROM conversation_entry WHERE id IN (?, ?, ?, ?, ?)",
                        Long.class,
                        absorbedFirst.getId(),
                        summary,
                        absorbedLast,
                        asked.question().getId(),
                        inThread))
                .as("each merged entry and the thread's entry stay, under the claiming account")
                .hasSize(5)
                .containsOnly(claimed);
        assertThat(jdbc.queryForObject(
                        "SELECT account_id FROM conversation_turn WHERE id = ?",
                        Long.class,
                        asked.turn().getId()))
                .as("the moved turn carries the claiming account")
                .isEqualTo(claimed);
    }

    @Test
    void shouldQueueTheAbsorbedRunningTurnAgainAndRefuseItsHostWhenBothMainsRunATurn() {
        Workspace shared = workspace("absorb-running");
        long claimed = claimedAccount("Claimed developer");
        long unclaimed = unclaimedSlackAccount();
        Conversation kept = conversations.findOrCreateMain(shared.getId(), claimed);
        Conversation absorbed = conversations.findOrCreateMain(shared.getId(), unclaimed);
        ConversationTurnService.Submission inApp =
                ask(shared, kept, ConversationTurn.Surface.APP, "Asked in the app", null);
        ConversationTurnService.Submission inSlack =
                ask(shared, absorbed, ConversationTurn.Surface.SLACK, "Asked in a Slack DM", "17.01");
        ConversationTurn appAttempt = start(inApp.turn().getId());
        UUID slackTurn = inSlack.turn().getId();
        ConversationTurn slackClaim = start(slackTurn);
        int slackAttempt = slackClaim.getAttempt();
        UUID slackLease = Objects.requireNonNull(slackClaim.getRunId());
        turnRepository.flush();

        people.absorbUnclaimed(verifiedClaim(claimed, unclaimed, identityOf(unclaimed)));

        assertThat(jdbc.queryForList(
                        "SELECT id FROM conversation_turn WHERE conversation_id = ? AND status = 'RUNNING'",
                        UUID.class,
                        kept.getId()))
                .as("the merged conversation runs one turn")
                .containsExactly(appAttempt.getId());
        assertThat(jdbc.queryForMap("SELECT status, conversation_id FROM conversation_turn WHERE id = ?", slackTurn))
                .as("the absorbed turn waits for its next attempt in the merged conversation")
                .containsEntry("status", "QUEUED")
                .containsEntry("conversation_id", kept.getId());
        assertThat(jdbc.queryForMap(
                        "SELECT conversation_id, redacted_at IS NULL AS kept FROM conversation_entry WHERE id = ?",
                        inSlack.question().getId()))
                .as("the question stays")
                .containsEntry("conversation_id", kept.getId())
                .containsEntry("kept", true);
        assertThatThrownBy(() -> turns.updatePartial(
                        shared.getId(),
                        slackTurn,
                        slackAttempt,
                        slackLease,
                        ConversationPartialUpdates.whole(JsonNodeFactory.instance.objectNode())))
                .as("the host of the absorbed attempt lost its lease")
                .isInstanceOf(ConversationLeaseLostException.class);
        assertThatThrownBy(() -> turns.end(
                        shared.getId(),
                        slackTurn,
                        slackAttempt,
                        slackLease,
                        new ConversationTurnService.Ending(
                                ConversationTurn.Status.DONE,
                                List.of(ConversationLedgerFixtures.answer(
                                        ConversationLedgerFixtures.piId(), "A stale answer")),
                                null,
                                null)))
                .isInstanceOf(ConversationLeaseLostException.class);
        assertThat(events.stream(
                        ConversationLedgerEvents.AttemptTakenBack.class,
                        ConversationLedgerEvents.AttemptTakenBack::turnId,
                        slackTurn))
                .as("the absorbed attempt is billed and its feedback claims settled after the merge commits")
                .singleElement()
                .satisfies(taken -> {
                    assertThat(taken.turnId()).isEqualTo(slackTurn);
                    assertThat(taken.attempt()).isEqualTo(slackAttempt);
                });
    }

    @Test
    void shouldStartMergedQuestionsInLedgerOrderRatherThanTheirOriginalRunOrder() {
        Workspace shared = workspace("absorb-order");
        long claimed = claimedAccount("Claimed developer");
        long unclaimed = unclaimedSlackAccount();
        Conversation kept = conversations.findOrCreateMain(shared.getId(), claimed);
        Conversation absorbed = conversations.findOrCreateMain(shared.getId(), unclaimed);
        var firstSubmitted = ask(shared, absorbed, ConversationTurn.Surface.SLACK, "Asked first", "17.01");
        var keptQuestion = ask(shared, kept, ConversationTurn.Surface.APP, "Kept first in the merged ledger", null);
        turnRepository.flush();

        people.absorbUnclaimed(verifiedClaim(claimed, unclaimed, identityOf(unclaimed)));

        ConversationTurn next = claims.claim(keptQuestion.turn());
        assertThat(next.getId()).isEqualTo(keptQuestion.turn().getId());
        turns.end(
                shared.getId(),
                next.getId(),
                next.getAttempt(),
                next.getRunId(),
                new ConversationTurnService.Ending(ConversationTurn.Status.DONE, List.of(), null, null));
        assertThat(claims.claim(keptQuestion.turn()).getId())
                .isEqualTo(firstSubmitted.turn().getId());
    }

    @Test
    void shouldFenceAStoppedHolderBeforeMovingItsConversation() {
        Workspace shared = workspace("absorb-stopped");
        long claimed = claimedAccount("Claimed developer");
        long unclaimed = unclaimedSlackAccount();
        Conversation kept = conversations.findOrCreateMain(shared.getId(), claimed);
        Conversation absorbed = conversations.findOrCreateMain(shared.getId(), unclaimed);
        var stoppedQuestion = ask(shared, absorbed, ConversationTurn.Surface.SLACK, "Stop this answer", "17.01");
        ConversationTurn holder = claims.claim(stoppedQuestion.turn());
        UUID run = holder.getRunId();
        int epoch = holder.getAttempt();
        turns.stop(shared.getId(), holder.getId());
        var keptQuestion = ask(shared, kept, ConversationTurn.Surface.APP, "Next answer", null);
        turnRepository.flush();

        people.absorbUnclaimed(verifiedClaim(claimed, unclaimed, identityOf(unclaimed)));

        assertThat(claims.claim(keptQuestion.turn()).getId())
                .isEqualTo(keptQuestion.turn().getId());
        assertThatThrownBy(() -> turns.updatePartial(
                        shared.getId(),
                        holder.getId(),
                        epoch,
                        run,
                        ConversationPartialUpdates.whole(JsonNodeFactory.instance.objectNode())))
                .isInstanceOf(ConversationLeaseLostException.class);
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM conversation_turn WHERE id = ?", String.class, holder.getId()))
                .isEqualTo("STOPPED");
    }

    @Test
    void shouldPurgeTurnsBeforeTheirRunsAndKeepAnotherWorkspacesConversation() {
        Workspace purged = workspace("purge-turn");
        Workspace kept = workspace("keep-turn");
        long developer = claimedAccount("Developer");
        var purgedConversation = conversations.findOrCreateMain(purged.getId(), developer);
        var keptConversation = conversations.findOrCreateMain(kept.getId(), developer);
        var deletedTurn = ask(purged, purgedConversation, ConversationTurn.Surface.APP, "Remove this", null)
                .turn();
        var keptTurn = ask(kept, keptConversation, ConversationTurn.Surface.APP, "Keep this", null)
                .turn();
        turns.stop(purged.getId(), deletedTurn.getId());
        UUID deletedRun = deletedTurn.getRunId();
        turnRepository.flush();

        lifecycle.purgeWorkspace(purged.getWorkspaceSlug());

        assertThat(jdbc.queryForList("SELECT id FROM conversation_turn WHERE id = ?", UUID.class, deletedTurn.getId()))
                .isEmpty();
        assertThat(jdbc.queryForList("SELECT id FROM agent_run WHERE id = ?", UUID.class, deletedRun))
                .isEmpty();
        assertThat(jdbc.queryForList("SELECT id FROM conversation_turn WHERE id = ?", UUID.class, keptTurn.getId()))
                .containsExactly(keptTurn.getId());
        assertThat(jdbc.queryForList("SELECT id FROM agent_run WHERE id = ?", UUID.class, keptTurn.getRunId()))
                .containsExactly(keptTurn.getRunId());
    }

    private ConversationTurnService.Submission ask(
            Workspace workspace,
            Conversation conversation,
            ConversationTurn.Surface surface,
            String text,
            @Nullable String slackTs) {
        member(workspace, conversation.getAccountId());
        return turns.submit(
                workspace.getId(),
                conversation.getId(),
                new ConversationTurnService.Question(
                        surface, text, UUID.randomUUID().toString(), slackTs, null));
    }

    /** Only a member asks Hephaestus in a workspace. */
    private void member(Workspace workspace, long accountId) {
        jdbc.update(
                "INSERT INTO membership(workspace_id, account_id, role) VALUES (?, ?, 'MEMBER') ON CONFLICT DO NOTHING",
                workspace.getId(),
                accountId);
    }

    /** Claims turns until this one starts. A turn of another test that starts first rolls back with this test. */
    private ConversationTurn start(UUID turnId) {
        while (true) {
            ConversationTurn claimed = claims.claimNext().orElseThrow();
            if (claimed.getId().equals(turnId)) {
                return claimed;
            }
        }
    }
}
