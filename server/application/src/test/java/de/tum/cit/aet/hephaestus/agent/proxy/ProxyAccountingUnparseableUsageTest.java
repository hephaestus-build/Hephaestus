package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

class ProxyAccountingUnparseableUsageTest extends BaseUnitTest {
    private static final UUID LEASE = UUID.fromString("508d958c-100e-594f-8b4e-6d5c60e59470");

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
                UsageSourceType.REVIEW_JOB, UUID.randomUUID(), 1, BigDecimal.ZERO, LEASE);
        byte[] notJson = "<html>502 upstream</html>".getBytes(StandardCharsets.UTF_8);

        assertThatCode(() -> accounting.recordUsage(attempt, null, LlmApiProtocol.OPENAI_COMPLETIONS, notJson))
                .as("accounting must never turn a call the provider already charged us for into an error")
                .doesNotThrowAnyException();

        assertThat(meterRegistry
                        .counter("llm.proxy.usage.unparseable", "sourceType", UsageSourceType.REVIEW_JOB.name())
                        .count())
                .as("a non-zero rate is the only signal that the ledger is under-billing")
                .isEqualTo(1.0);
        assertThat(missing(UsageSourceType.REVIEW_JOB.name()))
                .as("an unreadable body is not also an absent one")
                .isZero();
        verify(usageAccumulator, never()).accumulate(any(), any(), any());
    }

    @Test
    void shouldCountAServedBodyThatReportsNoUsageAsMissingNotUnparseable() {
        ProxyRouting.BilledAttempt attempt = new ProxyRouting.BilledAttempt(
                UsageSourceType.MENTOR_TURN, UUID.randomUUID(), 1, BigDecimal.ZERO, LEASE);
        byte[] body = "{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}".getBytes(StandardCharsets.UTF_8);

        accounting.recordUsage(attempt, null, LlmApiProtocol.OPENAI_COMPLETIONS, body);

        assertThat(missing("MENTOR_TURN")).isEqualTo(1.0);
        assertThat(meterRegistry
                        .counter("llm.proxy.usage.unparseable", "sourceType", "MENTOR_TURN")
                        .count())
                .isZero();
        verify(mentorTurnUsageAccumulator, never()).accumulate(any(), any());
    }

    @Test
    void shouldLogTheParseFailureTypeWithoutTheModelAnswer() {
        ProxyRouting.BilledAttempt attempt = new ProxyRouting.BilledAttempt(
                UsageSourceType.REVIEW_JOB, UUID.randomUUID(), 1, BigDecimal.ZERO, LEASE);
        // An unquoted token is what a parser quotes back in its message.
        byte[] answer = "{\"choices\":[{\"text\":privateanswercanary}]}".getBytes(StandardCharsets.UTF_8);
        Logger logger = (Logger) LoggerFactory.getLogger(ProxyAccounting.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            accounting.recordUsage(attempt, null, LlmApiProtocol.OPENAI_COMPLETIONS, answer);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.getFirst();
        assertThat(event.getFormattedMessage())
                .contains(attempt.sourceId().toString())
                .doesNotContain("canary");
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(event.getKeyValuePairs()).extracting(pair -> pair.key).containsExactly("event.name", "error.type");
        assertThat(event.getKeyValuePairs().toString()).doesNotContain("canary");
    }

    private double missing(String sourceType) {
        return meterRegistry
                .counter("llm.proxy.usage.missing", "sourceType", sourceType)
                .count();
    }

    @Test
    @DisplayName("a readable body still bills and leaves the counter alone")
    void shouldNotCountAParseableBody() {
        ProxyRouting.BilledAttempt attempt = new ProxyRouting.BilledAttempt(
                UsageSourceType.REVIEW_JOB, UUID.randomUUID(), 1, BigDecimal.ZERO, LEASE);
        byte[] body = """
            {"usage":{"prompt_tokens":10,"completion_tokens":5,\
            "prompt_tokens_details":{"cached_tokens":4},\
            "completion_tokens_details":{"reasoning_tokens":2}}}\
            """.getBytes(StandardCharsets.UTF_8);

        accounting.recordUsage(attempt, null, LlmApiProtocol.OPENAI_COMPLETIONS, body);

        assertThat(meterRegistry
                        .counter("llm.proxy.usage.unparseable", "sourceType", UsageSourceType.REVIEW_JOB.name())
                        .count())
                .as("the counter must mean 'unbilled', not 'a call happened'")
                .isZero();
        assertThat(missing(UsageSourceType.REVIEW_JOB.name())).isZero();
        verify(usageAccumulator).accumulate(attempt, null, new ProxyTokenUsage(6, 5, 2, 4, 0));
    }

    @Test
    void shouldCountInvalidTokenTotals() {
        ProxyRouting.BilledAttempt attempt = new ProxyRouting.BilledAttempt(
                UsageSourceType.REVIEW_JOB, UUID.randomUUID(), 1, BigDecimal.ZERO, LEASE);
        byte[] body = """
            {"usage":{"prompt_tokens":3,"prompt_tokens_details":{"cached_tokens":4}}}
            """.getBytes(StandardCharsets.UTF_8);

        accounting.recordUsage(attempt, null, LlmApiProtocol.OPENAI_COMPLETIONS, body);

        assertThat(meterRegistry
                        .counter("llm.proxy.usage.unparseable", "sourceType", UsageSourceType.REVIEW_JOB.name())
                        .count())
                .isEqualTo(1.0);
        verify(usageAccumulator, never()).accumulate(any(), any(), any());
    }

    @Test
    void shouldStillCountThePracticeScriptCallWhenItsBodyIsUnreadable() {
        ProxyRouting.BilledAttempt attempt = new ProxyRouting.BilledAttempt(
                UsageSourceType.PRACTICE_SCRIPT_EMBEDDING, UUID.randomUUID(), 0, BigDecimal.ZERO, LEASE);
        byte[] notJson = "<html>502 upstream</html>".getBytes(StandardCharsets.UTF_8);

        PracticeScriptCall call = new PracticeScriptCall(ModelKind.EMBEDDING, null, "comment-quality");

        accounting.recordUsage(attempt, call, LlmApiProtocol.OPENAI_EMBEDDINGS, notJson);

        assertThat(meterRegistry
                        .counter("llm.proxy.usage.unparseable", "sourceType", "PRACTICE_SCRIPT_EMBEDDING")
                        .count())
                .isEqualTo(1.0);
        verify(usageAccumulator).accumulate(attempt, call, null);
    }
}
