package de.tum.cit.aet.hephaestus.integration.slack.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessage;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThread;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class SlackAccountAbsorptionIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private SlackAccountAbsorptionContributor contributor;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private MembershipService memberships;

    @Autowired
    private SlackMessageRepository messages;

    @Autowired
    private SlackThreadRepository threads;

    @Autowired
    private EntityManager entities;

    @Test
    void absorptionMovesRealAuthorReferencesAndDeduplicatesParticipantsBeforeRemovingMemberships() {
        var workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace(
                "slack-absorption-" + UUID.randomUUID().toString().substring(0, 8)));
        var claimed = accounts.save(new Account("Claimed human"));
        var unclaimed = accounts.save(Account.unclaimed("Roster human"));
        var untouched = accounts.save(new Account("Other human"));
        long claimedId = Objects.requireNonNull(claimed.getId());
        long unclaimedId = Objects.requireNonNull(unclaimed.getId());
        long otherId = Objects.requireNonNull(untouched.getId());
        memberships.createMembership(workspace, claimedId, Membership.WorkspaceRole.MEMBER);
        memberships.createMembership(workspace, unclaimedId, Membership.WorkspaceRole.MEMBER);
        memberships.createMembership(workspace, otherId, Membership.WorkspaceRole.MEMBER);
        var thread = new SlackThread();
        thread.setWorkspaceId(workspace.getId());
        thread.setSlackChannelId("C1");
        thread.setSlackThreadTs("1700000000.000000");
        thread.setLastTs("1700000000.000000");
        thread.setMessageCount(1);
        thread.setParticipantAccountIds(new long[] {unclaimedId, claimedId, otherId});
        thread = threads.saveAndFlush(thread);
        var message = new SlackMessage();
        message.setWorkspaceId(workspace.getId());
        message.setSlackTeamId("T1");
        message.setSlackChannelId("C1");
        message.setSlackTs("1700000000.000000");
        message.setAuthorSlackUserId("U1");
        message.setAuthorAccountId(unclaimedId);
        message.setText("Original source text");
        message = messages.saveAndFlush(message);
        long messageId = message.getId();
        long threadId = thread.getId();
        long workspaceId = workspace.getId();
        contributor.absorb(claimedId, unclaimedId);
        contributor.finish(claimedId, unclaimedId);
        entities.clear();
        assertThat(messages.findById(messageId).orElseThrow().getAuthorAccountId())
                .isEqualTo(claimedId);
        assertThat(messages.findById(messageId).orElseThrow().getText()).isEqualTo("Original source text");
        assertThat(threads.findByWorkspaceIdAndId(workspaceId, threadId)
                        .orElseThrow()
                        .getParticipantAccountIds())
                .containsExactlyInAnyOrder(claimedId, otherId);
        assertThat(accounts.findById(unclaimedId)).isPresent();
    }
}
