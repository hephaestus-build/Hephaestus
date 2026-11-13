package de.tum.cit.aet.hephaestus.agent.productfeedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmAuthMode;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.proxy.ProductFeedbackModelGateway;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyRefusal;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.core.action.ActionStopReason;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDraftCalls;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDrafts;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import tools.jackson.databind.ObjectMapper;

class DraftReportAdapterTest extends BaseUnitTest {
    private static final UUID REPORT = UUID.fromString("0f3c1d2e-6a7b-4c8d-9e0f-1a2b3c4d5e6f");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final LlmModelResolver.InstanceModel MODEL = new LlmModelResolver.InstanceModel(
            new LlmModelResolver.ConnectionRef(UsageFundingSource.INSTANCE, 7L, 8L, null),
            new LlmModelResolver.ProxyCredential(
                    "https://api.openai.example/v1",
                    LlmApiProtocol.OPENAI_COMPLETIONS.wire(),
                    LlmAuthMode.BEARER,
                    "draft-upstream",
                    "key",
                    1L),
            LlmApiProtocol.OPENAI_COMPLETIONS,
            null);

    @Mock
    private ProductFeedbackDrafts drafts;

    @Mock
    private ProductFeedbackDraftCalls calls;

    @Mock
    private LlmModelResolver models;

    @Mock
    private ProductFeedbackModelGateway gateway;

    @Mock
    private LlmUsageRecorder usage;

    private DraftReportAdapter adapter;
    private final ActionAttempt attempt = new ActionAttempt(
            UUID.randomUUID(),
            null,
            ActionKind.DRAFT_REPORT,
            "draft:" + REPORT + ":2",
            MAPPER.valueToTree(new ProductFeedbackDrafts.Payload(REPORT, 2)),
            1,
            1L,
            false,
            null,
            () -> true,
            null);

    @BeforeEach
    void setUp() {
        adapter = new DraftReportAdapter(drafts, calls, models, Optional.of(gateway), MAPPER, Clock.systemUTC(), usage);
        lenient()
                .when(calls.waiting(REPORT, 2))
                .thenReturn(Optional.of(new ProductFeedbackDraftCalls.DraftCall(8L, "Draft it.", "The report.", 100)));
        lenient().when(drafts.claimed(attempt, REPORT, 2)).thenReturn(true);
        lenient().when(models.resolveInstanceModel(8L)).thenReturn(MODEL);
    }

    @Test
    void shouldKeepTheAnswerWhenTheCallIsServed() {
        when(gateway.draft(
                        eq(attempt.id()),
                        eq(new ProductFeedbackDrafts.Payload(REPORT, 2)),
                        eq(MODEL),
                        any(),
                        anyInt(),
                        any()))
                .thenReturn(served("{\"disposition\":\"ISSUE\"}"));
        when(drafts.answered(attempt, REPORT, 2, "{\"disposition\":\"ISSUE\"}")).thenReturn(true);

        assertThat(adapter.execute(attempt)).isInstanceOf(ActionResult.Done.class);
    }

    @Test
    void shouldStartTheTransportBudgetBeforeDatabaseReadsCanWait() {
        var started = Instant.parse("2026-11-12T00:00:00Z");
        var now = new AtomicReference<>(started);
        Clock delayed = mock(Clock.class);
        when(delayed.instant()).thenAnswer(ignored -> now.get());
        when(calls.waiting(REPORT, 2)).thenAnswer(ignored -> {
            now.set(started.plusSeconds(30));
            return Optional.of(new ProductFeedbackDraftCalls.DraftCall(8L, "Draft it.", "The report.", 100));
        });
        when(gateway.draft(
                        eq(attempt.id()),
                        eq(new ProductFeedbackDrafts.Payload(REPORT, 2)),
                        eq(MODEL),
                        any(),
                        anyInt(),
                        any()))
                .thenReturn(served("{}"));
        when(drafts.answered(attempt, REPORT, 2, "{}")).thenReturn(true);
        var bounded = new DraftReportAdapter(drafts, calls, models, Optional.of(gateway), MAPPER, delayed, usage);
        assertThat(bounded.execute(attempt)).isInstanceOf(ActionResult.Done.class);
        var deadline = ArgumentCaptor.forClass(Instant.class);
        verify(gateway)
                .draft(
                        eq(attempt.id()),
                        eq(new ProductFeedbackDrafts.Payload(REPORT, 2)),
                        eq(MODEL),
                        deadline.capture(),
                        anyInt(),
                        any());
        assertThat(deadline.getValue()).isEqualTo(started.plus(DraftReportAdapter.CALL_TIME));
    }

    @Test
    void shouldDeferCapacityWithoutSpendingAnAttempt() {
        when(gateway.draft(
                        eq(attempt.id()),
                        eq(new ProductFeedbackDrafts.Payload(REPORT, 2)),
                        eq(MODEL),
                        any(),
                        anyInt(),
                        any()))
                .thenReturn(new ProductFeedbackModelGateway.Answer(503, new byte[0], true, ProxyRefusal.CAPACITY));
        when(drafts.deferred(attempt, REPORT, 2)).thenReturn(true);
        assertThat(adapter.execute(attempt)).isInstanceOf(ActionResult.Defer.class);
        verify(drafts).deferred(attempt, REPORT, 2);
    }

    @Test
    void shouldRouteWithoutADraftWhenTheAgentRuntimeIsOff() {
        var disabled =
                new DraftReportAdapter(drafts, calls, models, Optional.empty(), MAPPER, Clock.systemUTC(), usage);
        assertThat(disabled.execute(attempt)).isEqualTo(new ActionResult.Failed(ActionStopReason.NOT_CONFIGURED));
    }

    @Test
    void shouldOfferTheModelNoTools() {
        when(gateway.draft(
                        eq(attempt.id()),
                        eq(new ProductFeedbackDrafts.Payload(REPORT, 2)),
                        eq(MODEL),
                        any(),
                        anyInt(),
                        any()))
                .thenReturn(served("{}"));
        when(drafts.answered(attempt, REPORT, 2, "{}")).thenReturn(true);

        adapter.execute(attempt);

        var body = ArgumentCaptor.forClass(byte[].class);
        verify(gateway)
                .draft(
                        eq(attempt.id()),
                        eq(new ProductFeedbackDrafts.Payload(REPORT, 2)),
                        eq(MODEL),
                        any(),
                        anyInt(),
                        body.capture());
        var request = MAPPER.readTree(new String(body.getValue(), StandardCharsets.UTF_8));
        assertThat(request.has("tools")).isFalse();
        assertThat(request.has("tool_choice")).isFalse();
        assertThat(request.path("model").asString()).isEqualTo("draft-upstream");
    }

    @Test
    void shouldGiveUpTheAttemptWhenItLostItsActionBeforeTheAnswerWasKept() {
        when(gateway.draft(
                        eq(attempt.id()),
                        eq(new ProductFeedbackDrafts.Payload(REPORT, 2)),
                        eq(MODEL),
                        any(),
                        anyInt(),
                        any()))
                .thenReturn(served("{}"));
        when(drafts.answered(attempt, REPORT, 2, "{}")).thenReturn(false);

        assertThat(adapter.execute(attempt)).isInstanceOf(ActionResult.Abandoned.class);
    }

    @Test
    void shouldRetryWhenTheProviderFails() {
        when(gateway.draft(
                        eq(attempt.id()),
                        eq(new ProductFeedbackDrafts.Payload(REPORT, 2)),
                        eq(MODEL),
                        any(),
                        anyInt(),
                        any()))
                .thenReturn(new ProductFeedbackModelGateway.Answer(503, new byte[0], true, null));

        assertThat(adapter.execute(attempt)).isInstanceOf(ActionResult.Retry.class);
        verify(drafts, never()).answered(any(), any(), anyInt(), anyString());
    }

    @Test
    void shouldRetryWhenTheProviderLimitsTheRate() {
        when(gateway.draft(
                        eq(attempt.id()),
                        eq(new ProductFeedbackDrafts.Payload(REPORT, 2)),
                        eq(MODEL),
                        any(),
                        anyInt(),
                        any()))
                .thenReturn(new ProductFeedbackModelGateway.Answer(429, new byte[0], true, null));

        assertThat(adapter.execute(attempt)).isInstanceOf(ActionResult.Retry.class);
    }

    @Test
    void shouldFailWhenTheProxyRefusesTheModel() {
        when(gateway.draft(
                        eq(attempt.id()),
                        eq(new ProductFeedbackDrafts.Payload(REPORT, 2)),
                        eq(MODEL),
                        any(),
                        anyInt(),
                        any()))
                .thenReturn(new ProductFeedbackModelGateway.Answer(403, new byte[0], true, ProxyRefusal.AI_CHOICE));

        assertThat(adapter.execute(attempt)).isEqualTo(new ActionResult.Failed(ActionStopReason.NOT_CONFIGURED));
    }

    @Test
    void shouldFailWithoutACallWhenTheModelIsGone() {
        when(models.resolveInstanceModel(8L)).thenReturn(null);

        assertThat(adapter.execute(attempt)).isEqualTo(new ActionResult.Failed(ActionStopReason.NOT_CONFIGURED));
        verify(gateway, never()).draft(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void shouldCancelWithoutACallWhenTheReportNoLongerWaits() {
        when(calls.waiting(REPORT, 2)).thenReturn(Optional.empty());

        assertThat(adapter.execute(attempt)).isEqualTo(new ActionResult.Cancelled(ActionStopReason.WITHDRAWN));
        verify(gateway, never()).draft(any(), any(), any(), any(), anyInt(), any());
    }

    private static ProductFeedbackModelGateway.Answer served(String text) {
        var body = MAPPER.createObjectNode();
        body.putArray("choices")
                .addObject()
                .putObject("message")
                .put("role", "assistant")
                .put("content", text);
        return new ProductFeedbackModelGateway.Answer(
                200, body.toString().getBytes(StandardCharsets.UTF_8), true, null);
    }
}
