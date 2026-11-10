package de.tum.cit.aet.hephaestus.agent.mentor.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.ConversationToolsPods;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnCompletion;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnStarter;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.TurnRunsAdapter;
import de.tum.cit.aet.hephaestus.agent.proxy.ConversationLoopProperties;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyCallsInFlight;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerNotifications;
import de.tum.cit.aet.hephaestus.mentor.MentorRedaction;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnProgress;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class HephTurnConfigurationTest extends BaseUnitTest {
    private HephTurns turns(ConversationLoopProperties loop) {
        return new HephTurnConfiguration()
                .hephTurns(
                        mock(MentorTurnStarter.class),
                        mock(MentorTurnService.class),
                        mock(MentorTurnRepository.class),
                        mock(MentorRedaction.class),
                        mock(MentorTurnCompletion.class),
                        mock(MentorTurnProgress.class),
                        mock(MentorLedgerNotifications.class),
                        mock(ProxyCallsInFlight.class),
                        mock(ConversationToolsPods.class),
                        mock(AttemptTokenIssuer.class),
                        mock(TurnRunsAdapter.class),
                        loop,
                        mock(Clock.class),
                        new SimpleMeterRegistry());
    }

    @Test
    void shouldRefuseAgentStartupWithoutTheLoopSecret() {
        assertThatThrownBy(() -> turns(new ConversationLoopProperties(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("require hephaestus.conversation-loop.secret");
    }

    @Test
    void shouldRefuseAnInvalidSecretWithoutDisclosingIt() {
        assertThatThrownBy(() -> turns(new ConversationLoopProperties("private-invalid-secret")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("private-invalid-secret");
    }

    @Test
    void shouldCreateTheTurnRuntimeWithAValidLoopSecret() {
        assertThat(turns(new ConversationLoopProperties("a".repeat(64)))).isNotNull();
    }
}
