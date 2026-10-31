package de.tum.cit.aet.hephaestus.agent.mentor.turn;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MentorTurnQueueSamplerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private MentorTurnService turns;

    @Autowired
    private MentorTurnQueueSampler sampler;

    @Autowired
    private MeterRegistry metrics;

    @Test
    void shouldCountTheTurnsThatWaitForAWorkerAndNotTheOneThatRuns() {
        User developer = persistUser("heph-queue-developer");
        Workspace workspace = createWorkspace("heph-queue", "Hephaestus", "heph-queue-org", AccountType.ORG, developer);
        long accountId = accountId(developer);
        MentorConversation main = conversations.findOrCreateMain(workspace.getId(), accountId);
        sampler.sample();
        double before = queued();

        MentorTurnStarts.askAndStart(turns, metrics, workspace.getId(), main.getId(), "First");
        ask(workspace, main, "Second");
        ask(workspace, main, "Third");
        sampler.sample();

        assertThat(queued()).isEqualTo(before + 2);
    }

    private void ask(Workspace workspace, MentorConversation main, String question) {
        turns.submit(
                workspace.getId(),
                main.getId(),
                new MentorTurnService.Question(
                        MentorTurn.Surface.APP, question, UUID.randomUUID().toString(), null, null));
    }

    private double queued() {
        return metrics.get(AgentMetrics.MENTOR_TURN_QUEUED).gauge().value();
    }
}
