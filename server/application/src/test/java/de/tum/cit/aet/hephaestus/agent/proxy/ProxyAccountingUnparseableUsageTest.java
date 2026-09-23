package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageSourceType;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ProxyAccountingUnparseableUsageTest extends BaseUnitTest {

    private final ProxyBudgetGate budgetGate = mock(ProxyBudgetGate.class);
    private final ProxyUsageAccumulator usageAccumulator = mock(ProxyUsageAccumulator.class);
    private final MentorTurnUsageAccumulator mentorTurnUsageAccumulator = mock(MentorTurnUsageAccumulator.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private final ProxyAccounting accounting = new ProxyAccounting(
            budgetGate, usageAccumulator, mentorTurnUsageAccumulator, meterRegistry, new ObjectMapper());

    @Test
    @DisplayName("an unreadable 2xx body is counted, not silently dropped")
    void shouldCountUnparseableUpstreamBody() {
        ProxyRouting.BilledAttempt attempt = new ProxyRouting.BilledAttempt(
                LlmUsageSourceType.AGENT_JOB, UUID.randomUUID(), 1, BigDecimal.ZERO, "worker-1");
        byte[] notJson = "<html>502 upstream</html>".getBytes(StandardCharsets.UTF_8);

        assertThatCode(() -> accounting.recordUsage(attempt, notJson, false))
                .as("accounting must never turn a call the provider already charged us for into an error")
                .doesNotThrowAnyException();

        assertThat(meterRegistry
                        .counter("llm.proxy.usage.unparseable", "sourceType", "AGENT_JOB")
                        .count())
                .as("a non-zero rate is the only signal that the ledger is under-billing")
                .isEqualTo(1.0);
        verify(usageAccumulator, never()).accumulate(any(), any());
    }

    @Test
    @DisplayName("a readable body still bills and leaves the counter alone")
    void shouldNotCountAParseableBody() {
        ProxyRouting.BilledAttempt attempt = new ProxyRouting.BilledAttempt(
                LlmUsageSourceType.AGENT_JOB, UUID.randomUUID(), 1, BigDecimal.ZERO, "worker-1");
        byte[] body = ("""
            {"usage":{"prompt_tokens":10,"completion_tokens":5,\
            "prompt_tokens_details":{"cached_tokens":4},\
            "completion_tokens_details":{"reasoning_tokens":2}}}\
            """).getBytes(StandardCharsets.UTF_8);

        accounting.recordUsage(attempt, body, false);

        assertThat(meterRegistry
                        .counter("llm.proxy.usage.unparseable", "sourceType", "AGENT_JOB")
                        .count())
                .as("the counter must mean 'unbilled', not 'a call happened'")
                .isZero();
        verify(usageAccumulator).accumulate(attempt, new ProxyTokenUsage(6, 5, 2, 4, 0));
    }

    @Test
    void shouldCountInvalidTokenTotals() {
        ProxyRouting.BilledAttempt attempt = new ProxyRouting.BilledAttempt(
                LlmUsageSourceType.AGENT_JOB, UUID.randomUUID(), 1, BigDecimal.ZERO, "worker-1");
        byte[] body = ("""
            {"usage":{"prompt_tokens":3,"prompt_tokens_details":{"cached_tokens":4}}}
            """).getBytes(StandardCharsets.UTF_8);

        accounting.recordUsage(attempt, body, false);

        assertThat(meterRegistry
                        .counter("llm.proxy.usage.unparseable", "sourceType", "AGENT_JOB")
                        .count())
                .isEqualTo(1.0);
        verify(usageAccumulator, never()).accumulate(any(), any());
    }
}
