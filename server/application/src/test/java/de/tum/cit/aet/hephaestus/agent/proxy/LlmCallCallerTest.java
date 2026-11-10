package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPermitRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolProperties;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunBand;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.support.TransactionTemplate;

class LlmCallCallerTest extends BaseUnitTest {
    private final LlmCallPermits permits = new LlmCallPermits(
            mock(LlmCallPoolRepository.class),
            mock(LlmCallPermitRepository.class),
            mock(TransactionTemplate.class),
            new LlmCallPoolProperties(Duration.ofSeconds(20), Duration.ofSeconds(30)),
            LlmCallPermitsTestFixtures.PERMIT_AGING,
            "caller-test",
            new SimpleMeterRegistry(),
            mock(LlmModelResolver.class));

    @ParameterizedTest
    @EnumSource(value = AgentRunBand.class, names = "BACKGROUND", mode = EnumSource.Mode.EXCLUDE)
    void shouldRejectACallWithoutAWorkspaceWhenItIsNotInTheBackgroundBand(AgentRunBand band) {
        var caller = new LlmCallPermits.Caller(null, UUID.randomUUID(), 0, null);
        assertThatThrownBy(() -> permits.acquire(1, band, caller, () -> false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A call without a workspace is product feedback, in the background band");
    }
}
