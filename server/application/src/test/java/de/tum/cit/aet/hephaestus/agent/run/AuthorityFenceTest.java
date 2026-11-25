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
import de.tum.cit.aet.hephaestus.core.authority.AuthorityBoundary;
import de.tum.cit.aet.hephaestus.core.authority.AuthorityRefusal;
import de.tum.cit.aet.hephaestus.core.authority.AuthorityRefusedException;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

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
