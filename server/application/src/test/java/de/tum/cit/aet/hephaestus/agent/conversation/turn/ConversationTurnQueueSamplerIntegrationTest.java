package de.tum.cit.aet.hephaestus.agent.conversation.turn;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.conversation.Conversation;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

class ConversationTurnQueueSamplerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private ApplicationContext beans;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ConversationTurnService turns;

    @Autowired
    private ConversationTurnQueueSampler sampler;

    @Autowired
    private MeterRegistry metrics;

    @Test
    void shouldCountTheTurnsThatWaitForAWorkerAndNotTheOneThatRuns() {
        User developer = persistUser("ask-queue-developer");
        Workspace workspace = createWorkspace("ask-queue", "Hephaestus", "ask-queue-org", AccountType.ORG, developer);
        long accountId = accountId(developer);
        Conversation main = conversations.findOrCreateMain(workspace.getId(), accountId);
        sampler.sample();
        double before = queued();

        ConversationTurnStarts.askAndStart(beans, metrics, workspace.getId(), main.getId(), "First");
        ask(workspace, main, "Second");
        ask(workspace, main, "Third");
        sampler.sample();

        assertThat(queued()).isEqualTo(before + 2);
    }

    private void ask(Workspace workspace, Conversation main, String question) {
        turns.submit(
                workspace.getId(),
                main.getId(),
                new ConversationTurnService.Question(
                        ConversationTurn.Surface.APP,
                        question,
                        UUID.randomUUID().toString(),
                        null,
                        null));
    }

    private double queued() {
        return metrics.get(AgentMetrics.CONVERSATION_TURN_QUEUED).gauge().value();
    }
}
