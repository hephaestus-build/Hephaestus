package de.tum.cit.aet.hephaestus.agent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationTurnCompletion;
import de.tum.cit.aet.hephaestus.conversation.Conversation;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnClaims;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.core.action.ActionExecutor;
import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionRequest;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.core.action.ActionStopReason;
import de.tum.cit.aet.hephaestus.core.action.ActionTestSupport;
import de.tum.cit.aet.hephaestus.core.action.AgentActions;
import de.tum.cit.aet.hephaestus.core.authority.AuthorityBoundary;
import de.tum.cit.aet.hephaestus.core.authority.AuthorityRefusal;
import de.tum.cit.aet.hephaestus.core.authority.AuthorityRefusedException;
import de.tum.cit.aet.hephaestus.core.authority.spi.AuthorityGenerations;
import de.tum.cit.aet.hephaestus.core.authority.spi.CheckedAuthority;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.AuthorityFenceCase;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The authorization recheck (ADR 0134): a narrowing of a workspace's authority refuses a run's next boundary with its
 * reason, and ends the run with its domain row. Each boundary has its case.
 */
class AuthorityFenceTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private AgentRunAuthority authority;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ConversationTurnService turns;

    @Autowired
    private ConversationTurnRepository turnRepository;

    @Autowired
    private ConversationTurnClaims claims;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AgentActions actions;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private AuthorityGenerations generations;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private AgentRuns agentRuns;

    private Workspace workspace;
    private long asker;
    private Conversation conversation;

    @BeforeEach
    void setUpMember() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User owner = persistUser("fence-owner-" + suffix);
        workspace = createWorkspace("fence-" + suffix, "Fence", "fence-org-" + suffix, AccountType.ORG, owner);
        User member = persistUser("fence-member-" + suffix);
        ensureMembership(workspace, member, Membership.WorkspaceRole.MEMBER);
        asker = accountId(member);
        conversation = conversations.findOrCreateMain(workspace.getId(), asker);
    }

    @Test
    @AuthorityFenceCase(setting = Membership.class, boundary = AuthorityBoundary.CONTEXT_READ)
    void shouldRefuseTheNextContextReadWhenTheAskerLostTheirMembership() {
        ConversationTurn turn = runningTurn();
        AgentRunFence fence = fence(turn);

        jdbc.update("DELETE FROM membership WHERE workspace_id = ? AND account_id = ?", workspace.getId(), asker);

        var refused = catchThrowableOfType(
                AuthorityRefusedException.class, () -> authority.require(fence, AuthorityBoundary.CONTEXT_READ));
        assertThat(refused.refusal())
                .isEqualTo(new AuthorityRefusal(
                        AuthorityBoundary.CONTEXT_READ, AuthorityRefusal.Reason.WORKSPACE_AUTHORITY_NARROWED));
        assertThat(jdbc.queryForObject(
                        "SELECT state || '/' || stop_reason FROM agent_run WHERE id = ? AND workspace_id = ?",
                        String.class,
                        fence.runId(),
                        workspace.getId()))
                .isEqualTo("ENDED/AUTHORITY_CHANGED");
        ConversationTurn ended = turnRepository.findById(turn.getId()).orElseThrow();
        assertThat(ended.getStatus()).isEqualTo(ConversationTurn.Status.FAILED);
        assertThat(ended.getError()).isEqualTo(ConversationTurnCompletion.ACCESS_CHANGED);
    }

    @Test
    @AuthorityFenceCase(setting = Membership.class, boundary = AuthorityBoundary.SEND)
    void shouldRefuseAQueuedSendWithoutReachingTheProviderWhenTheMemberLostTheirMembership() {
        var provider = new CopyOnWriteArrayList<UUID>();
        var executor = sendingTo(provider);
        UUID action = actions.enqueue(notice());

        jdbc.update("DELETE FROM membership WHERE workspace_id = ? AND account_id = ?", workspace.getId(), asker);

        assertThat(executor.runNow(action)).contains(new ActionResult.Cancelled(ActionStopReason.AUTHORITY_CHANGED));
        assertThat(provider).isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT state || '/' || stop_reason FROM agent_action WHERE id = ? AND workspace_id = ?",
                        String.class,
                        action,
                        workspace.getId()))
                .isEqualTo("CANCELLED/AUTHORITY_CHANGED");
    }

    @Test
    void shouldRefuseASendWhoseNarrowingCommittedBetweenItsAccessCheckAndItsInsert() {
        var provider = new CopyOnWriteArrayList<UUID>();
        var executor = sendingTo(provider);

        UUID action = Objects.requireNonNull(new TransactionTemplate(transactions).execute(status -> {
            CheckedAuthority checked = generations
                    .authorize(workspace.getId(), () -> {
                        // The member leaves on another connection after the stamp was read, and the check still
                        // passes in this transaction.
                        PostgreSQLTestContainer.ownerJdbc()
                                .update(
                                        "DELETE FROM membership WHERE workspace_id = ? AND account_id = ?",
                                        workspace.getId(),
                                        asker);
                        return true;
                    })
                    .orElseThrow();
            return actions.enqueue(notice(checked));
        }));

        assertThat(executor.runNow(action)).contains(new ActionResult.Cancelled(ActionStopReason.AUTHORITY_CHANGED));
        assertThat(provider).isEmpty();
    }

    @Test
    void shouldMakeANarrowingWaitWhileAWriteIsAdmittedUnderTheGeneration() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(generations.workspaceHeld(workspace.getId())).hasValue(generation());

            assertThatThrownBy(
                            () -> PostgreSQLTestContainer.ownerJdbc().execute("""
                            DO $$ BEGIN
                                SET LOCAL lock_timeout = '300ms';
                                DELETE FROM membership WHERE workspace_id = %d AND account_id = %d;
                            END $$""".formatted(workspace.getId(), asker)))
                    .hasMessageContaining("lock timeout");
        });
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM membership WHERE workspace_id = ? AND account_id = ?",
                        Long.class,
                        workspace.getId(),
                        asker))
                .isEqualTo(1);
    }

    @Test
    void shouldSendAnActionQueuedAfterTheNarrowing() {
        var provider = new CopyOnWriteArrayList<UUID>();
        var executor = sendingTo(provider);
        jdbc.update("DELETE FROM membership WHERE workspace_id = ? AND account_id = ?", workspace.getId(), asker);
        UUID action = actions.enqueue(notice());

        assertThat(executor.runNow(action)).contains(new ActionResult.Done(null));
        assertThat(provider).containsExactly(action);
    }

    @Test
    void shouldRefuseAModelCallUnderTheRunsLockWhenTheMemberLeftWhileItWaited() {
        AgentRunFence fence = fence(runningTurn());
        // The narrowing commits while the call would still wait for the run's lock.
        jdbc.update("DELETE FROM membership WHERE workspace_id = ? AND account_id = ?", workspace.getId(), asker);

        var refused = catchThrowableOfType(
                AuthorityRefusedException.class,
                () -> new TransactionTemplate(transactions)
                        .executeWithoutResult(
                                status -> agentRuns.requireCurrentUnder(fence, AuthorityBoundary.MODEL_DISPATCH)));

        assertThat(refused.refusal())
                .isEqualTo(new AuthorityRefusal(
                        AuthorityBoundary.MODEL_DISPATCH, AuthorityRefusal.Reason.WORKSPACE_AUTHORITY_NARROWED));
    }

    @Test
    void shouldRefuseAQuestionWhoseOwnerLeftBeforeItsRunWasStamped() {
        jdbc.update(
                "UPDATE membership SET left_at = now() WHERE workspace_id = ? AND account_id = ?",
                workspace.getId(),
                asker);

        assertThatThrownBy(() -> runningTurn()).isInstanceOf(EntityNotFoundException.class);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM conversation_turn WHERE workspace_id = ? AND conversation_id = ?",
                        Long.class,
                        workspace.getId(),
                        conversation.getId()))
                .isZero();
    }

    @Test
    void shouldLetTheContextReadPassWhenAuthorityOnlyWidened() {
        ConversationTurn turn = runningTurn();
        User joining =
                persistUser("fence-joining-" + UUID.randomUUID().toString().substring(0, 8));
        ensureMembership(workspace, joining, Membership.WorkspaceRole.MEMBER);
        jdbc.update(
                "UPDATE membership SET role = 'ADMIN' WHERE workspace_id = ? AND account_id = ?",
                workspace.getId(),
                asker);

        assertThatCode(() -> authority.require(fence(turn), AuthorityBoundary.CONTEXT_READ))
                .doesNotThrowAnyException();
        assertThat(turnRepository.findById(turn.getId()).orElseThrow().getStatus())
                .isEqualTo(ConversationTurn.Status.RUNNING);
    }

    @Test
    void shouldRaiseTheGenerationWhenAMemberLosesARole() {
        jdbc.update(
                "UPDATE membership SET role = 'ADMIN' WHERE workspace_id = ? AND account_id = ?",
                workspace.getId(),
                asker);
        long before = generation();

        jdbc.update(
                "UPDATE membership SET role = 'MEMBER' WHERE workspace_id = ? AND account_id = ?",
                workspace.getId(),
                asker);

        assertThat(generation()).isEqualTo(before + 1);
    }

    @Test
    void shouldRaiseTheGenerationWhenAMemberLeaves() {
        long before = generation();

        jdbc.update(
                "UPDATE membership SET left_at = now() WHERE workspace_id = ? AND account_id = ?",
                workspace.getId(),
                asker);

        assertThat(generation()).isEqualTo(before + 1);
    }

    @Test
    void shouldRefuseToChangeARunsStamp() {
        AgentRunFence fence = fence(runningTurn());

        assertThatCode(() -> jdbc.update(
                        "UPDATE agent_run SET authorization_stamp = '{\"workspace\": 99}' WHERE id = ? AND workspace_id = ?",
                        fence.runId(),
                        workspace.getId()))
                .hasMessageContaining("work keeps the authorization stamp it was requested under");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "[]",
                "{\"workspace\": null}",
                "{\"workspace\": \"1\"}",
                "{\"workspace\": 1.5}",
                "{\"workspace\": -1}",
                "{\"workspace\": 9223372036854775808}"
            })
    void shouldRefuseAStampWithoutAWholeWorkspaceGeneration(String stamp) {
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO agent_run(id, workspace_id, kind, band, concurrency_key, state, attempt, lease_epoch,"
                                + " available_at, created_at, authorization_stamp)"
                                + " VALUES (?, ?, 'TURN', 'INTERACTIVE', ?, 'QUEUED', 0, 0, now(), now(), CAST(? AS jsonb))",
                        UUID.randomUUID(),
                        workspace.getId(),
                        "conversation:" + UUID.randomUUID(),
                        stamp))
                .hasMessageContaining("ck_agent_run_authorization_stamp");
    }

    @Test
    void shouldAcceptTheLargestWorkspaceGeneration() {
        assertThatCode(() -> jdbc.update(
                        "INSERT INTO agent_run(id, workspace_id, kind, band, concurrency_key, state, attempt, lease_epoch,"
                                + " available_at, created_at, authorization_stamp)"
                                + " VALUES (?, ?, 'TURN', 'INTERACTIVE', ?, 'QUEUED', 0, 0, now(), now(),"
                                + " '{\"workspace\": 9223372036854775807}')",
                        UUID.randomUUID(),
                        workspace.getId(),
                        "conversation:" + UUID.randomUUID()))
                .doesNotThrowAnyException();
    }

    /** The executor with a recording fake in place of the Slack notice adapter: it records each write it starts. */
    private ActionExecutor sendingTo(List<UUID> provider) {
        return ActionTestSupport.withAdapter(context, ActionTestSupport.adapter(ActionKind.SLACK_NOTICE, attempt -> {
            if (!attempt.fence().beginWrite()) return new ActionResult.Abandoned();
            provider.add(attempt.id());
            return new ActionResult.Done(null);
        }));
    }

    private ActionRequest notice() {
        return notice(generations.requestedNow(workspace.getId()));
    }

    private ActionRequest notice(CheckedAuthority authority) {
        String key = "authority-fence:" + UUID.randomUUID();
        return new ActionRequest(
                ActionKind.SLACK_NOTICE,
                authority,
                key,
                key,
                JsonNodeFactory.instance.objectNode().put("n", 1),
                asker,
                3,
                null,
                null);
    }

    private long generation() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT authority_generation FROM workspace WHERE id = ?", Long.class, workspace.getId()));
    }

    /** A question of the member, whose turn a sandbox holds now. */
    private ConversationTurn runningTurn() {
        ConversationTurn queued = turns.submit(
                        workspace.getId(),
                        conversation.getId(),
                        new ConversationTurnService.Question(
                                ConversationTurn.Surface.APP,
                                "What changed?",
                                UUID.randomUUID().toString(),
                                null,
                                null))
                .turn();
        return claims.claim(queued);
    }

    private AgentRunFence fence(ConversationTurn turn) {
        return new AgentRunFence(Objects.requireNonNull(turn.getRunId()), workspace.getId(), turn.getAttempt());
    }
}
