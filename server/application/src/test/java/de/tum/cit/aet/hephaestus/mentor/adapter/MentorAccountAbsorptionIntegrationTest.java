package de.tum.cit.aet.hephaestus.mentor.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryDraft;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryService;
import de.tum.cit.aet.hephaestus.mentor.MentorLeaseLostException;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerEvents;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerFixtures;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Absorbing an unclaimed account moves its Heph conversations to the claiming account. Where both have a main
 * conversation in a workspace, the absorbed one merges into the claiming one ({@link MentorAccountAbsorptionContributor}).
 */
@Transactional
@RecordApplicationEvents
class MentorAccountAbsorptionIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ApplicationEvents events;

    @Autowired
    private MentorAccountAbsorptionContributor contributor;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private IdentityRepository identities;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private MentorEntryService entries;

    @Autowired
    private MentorTurnService turns;

    @Autowired
    private MentorTurnRepository turnRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private static final Duration LEASE = Duration.ofMinutes(5);

    private Workspace workspace(String name) {
        return workspaces.save(WorkspaceTestFixtures.activeWorkspace(
                name + "-" + UUID.randomUUID().toString().substring(0, 8)));
    }

    /** An account with one Slack identity, as the person-data write fence requires. */
    private long account(String name, Account.Status status) {
        var account = new Account(name);
        account.setStatus(status);
        account = accounts.save(account);
        var slack = providers
                .findByTypeAndServerUrl(IdentityProviderType.SLACK, "https://slack.com")
                .orElseGet(() -> providers.save(new IdentityProvider(IdentityProviderType.SLACK, "https://slack.com")));
        var identity = new Identity();
        identity.setAccount(account);
        identity.setProviderId(Objects.requireNonNull(slack.getId()));
        identity.setProviderUserKey("U" + UUID.randomUUID().toString().substring(0, 8));
        identity.setTeamKey("T1");
        identities.save(identity);
        return Objects.requireNonNull(account.getId());
    }

    private MentorEntry heph(Workspace workspace, MentorConversation conversation, String text) {
        return entries.append(workspace.getId(), conversation.getId(), MentorLedgerFixtures.heph(text));
    }

    /** The conversation's entries in order: their ids, and whether a redaction erased them. */
    private record Row(UUID id, long seq, boolean redacted) {}

    private List<Row> rows(UUID conversationId) {
        return jdbc.query(
                "SELECT id, seq, redacted_at IS NOT NULL FROM mentor_entry WHERE conversation_id = ? ORDER BY seq",
                (rs, row) ->
                        new Row(Objects.requireNonNull(rs.getObject(1, UUID.class)), rs.getLong(2), rs.getBoolean(3)),
                conversationId);
    }

    @Test
    void shouldMergeTheAbsorbedMainAfterTheClaimingMainAndMoveEveryOtherConversation() {
        Workspace shared = workspace("absorb-shared");
        Workspace onlyAbsorbed = workspace("absorb-only");
        long claimed = account("Claimed developer", Account.Status.ACTIVE);
        long unclaimed = account("Slack developer", Account.Status.UNCLAIMED);

        MentorConversation kept = conversations.findOrCreateMain(shared.getId(), claimed);
        UUID keptFirst = heph(shared, kept, "Kept one").getId();
        UUID keptSecond = heph(shared, kept, "Kept two").getId();
        MentorConversation absorbed = conversations.findOrCreateMain(shared.getId(), unclaimed);
        MentorEntry absorbedFirst = heph(shared, absorbed, "Absorbed one");
        UUID summary = entries.append(
                        shared.getId(),
                        absorbed.getId(),
                        new MentorEntryDraft(
                                MentorEntry.Kind.SUMMARY,
                                MentorEntry.Surface.SYSTEM,
                                "What was said before.",
                                MentorLedgerFixtures.compaction(MentorLedgerFixtures.piId(), "What was said.", "x"),
                                null,
                                null,
                                null))
                .getId();
        UUID absorbedLast = heph(shared, absorbed, "Absorbed two").getId();
        MentorConversation thread = conversations.openThread(shared.getId(), absorbedFirst.getId());
        heph(shared, thread, "In the thread");
        MentorTurnService.Submission asked = turns.submit(
                shared.getId(),
                absorbed.getId(),
                new MentorTurnService.Question(
                        MentorTurn.Surface.APP,
                        "Still there?",
                        UUID.randomUUID().toString(),
                        null,
                        null));
        MentorConversation elsewhere = conversations.findOrCreateMain(onlyAbsorbed.getId(), unclaimed);

        contributor.absorb(claimed, unclaimed);
        contributor.finish(claimed, unclaimed);

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM mentor_conversation WHERE id = ?", Long.class, absorbed.getId()))
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
                        "SELECT parent_conversation_id, parent_entry_id, account_id FROM mentor_conversation WHERE id = ?",
                        thread.getId()))
                .as("the thread follows its parent message")
                .containsEntry("parent_conversation_id", kept.getId())
                .containsEntry("parent_entry_id", absorbedFirst.getId())
                .containsEntry("account_id", claimed);
        assertThat(jdbc.queryForObject(
                        "SELECT conversation_id FROM mentor_turn WHERE id = ?",
                        UUID.class,
                        asked.turn().getId()))
                .isEqualTo(kept.getId());
        assertThat(jdbc.queryForObject(
                        "SELECT account_id FROM mentor_conversation WHERE id = ?", Long.class, elsewhere.getId()))
                .as("a main without a clash moves")
                .isEqualTo(claimed);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM mentor_conversation WHERE account_id = ?", Long.class, unclaimed))
                .isZero();
    }

    @Test
    void shouldQueueTheAbsorbedRunningTurnAgainAndRefuseItsHostWhenBothMainsRunATurn() {
        Workspace shared = workspace("absorb-running");
        long claimed = account("Claimed developer", Account.Status.ACTIVE);
        long unclaimed = account("Slack developer", Account.Status.UNCLAIMED);
        MentorConversation kept = conversations.findOrCreateMain(shared.getId(), claimed);
        MentorConversation absorbed = conversations.findOrCreateMain(shared.getId(), unclaimed);
        MentorTurnService.Submission inApp = ask(shared, kept, MentorTurn.Surface.APP, "Asked in the app", null);
        MentorTurnService.Submission inSlack =
                ask(shared, absorbed, MentorTurn.Surface.SLACK, "Asked in a Slack DM", "17.01");
        MentorTurn appAttempt = start(inApp.turn().getId());
        UUID slackTurn = inSlack.turn().getId();
        MentorTurn slackClaim = start(slackTurn);
        int slackAttempt = slackClaim.getAttempt();
        UUID slackLease = Objects.requireNonNull(slackClaim.getLeaseId());
        turnRepository.flush();

        contributor.absorb(claimed, unclaimed);
        contributor.finish(claimed, unclaimed);

        assertThat(jdbc.queryForList(
                        "SELECT id FROM mentor_turn WHERE conversation_id = ? AND status = 'RUNNING'",
                        UUID.class,
                        kept.getId()))
                .as("the merged conversation runs one turn")
                .containsExactly(appAttempt.getId());
        assertThat(jdbc.queryForMap("SELECT status, conversation_id FROM mentor_turn WHERE id = ?", slackTurn))
                .as("the absorbed turn waits for its next attempt in the merged conversation")
                .containsEntry("status", "QUEUED")
                .containsEntry("conversation_id", kept.getId());
        assertThat(jdbc.queryForMap(
                        "SELECT conversation_id, redacted_at IS NULL AS kept FROM mentor_entry WHERE id = ?",
                        inSlack.question().getId()))
                .as("the question stays")
                .containsEntry("conversation_id", kept.getId())
                .containsEntry("kept", true);
        assertThatThrownBy(() -> turns.updatePartial(
                        shared.getId(), slackTurn, slackAttempt, slackLease, JsonNodeFactory.instance.objectNode()))
                .as("the host of the absorbed attempt lost its lease")
                .isInstanceOf(MentorLeaseLostException.class);
        assertThatThrownBy(() -> turns.end(
                        shared.getId(),
                        slackTurn,
                        slackAttempt,
                        slackLease,
                        new MentorTurnService.Ending(
                                MentorTurn.Status.DONE,
                                List.of(MentorLedgerFixtures.answer(MentorLedgerFixtures.piId(), "A stale answer")), null,
                                null)))
                .isInstanceOf(MentorLeaseLostException.class);
        assertThat(events.stream(MentorLedgerEvents.AttemptTakenBack.class))
                .as("the absorbed attempt is billed and its feedback claims settled after the merge commits")
                .singleElement()
                .satisfies(taken -> {
                    assertThat(taken.turnId()).isEqualTo(slackTurn);
                    assertThat(taken.attempt()).isEqualTo(slackAttempt);
                });
    }

    private MentorTurnService.Submission ask(
            Workspace workspace,
            MentorConversation conversation,
            MentorTurn.Surface surface,
            String text,
            @Nullable String slackTs) {
        return turns.submit(
                workspace.getId(),
                conversation.getId(),
                new MentorTurnService.Question(surface, text, UUID.randomUUID().toString(), slackTs, null));
    }

    /** Claims turns until this one starts. A turn of another test that starts first rolls back with this test. */
    private MentorTurn start(UUID turnId) {
        while (true) {
            MentorTurn claimed = turns.claimNext(LEASE).orElseThrow();
            if (claimed.getId().equals(turnId)) {
                return claimed;
            }
        }
    }
}
