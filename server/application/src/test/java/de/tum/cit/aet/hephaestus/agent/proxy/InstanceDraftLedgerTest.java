package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.run.StaleRunException;
import de.tum.cit.aet.hephaestus.agent.usage.LlmAdmissionService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.agent.usage.ModelCallFailure;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.conversation.spi.ConversationTurnAccess;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDraftCalls;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

class InstanceDraftLedgerTest extends BaseUnitTest {
    @Test
    void shouldOpenInTheSharedLedgerAndCountOnlyTheFirstMeasurement() {
        var recorder = mock(LlmUsageRecorder.class);
        var admission = mock(LlmAdmissionService.class);
        var drafts = mock(ProductFeedbackDraftCalls.class);
        var runs = mock(AgentRuns.class);
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var ledger = new ProxyCallLedger(
                admission,
                drafts,
                recorder,
                runs,
                mock(ReviewJobRepository.class),
                mock(ConversationTurnAccess.class),
                mock(ProxyBudgetGate.class),
                new ObjectMapper(),
                new SimpleMeterRegistry(),
                transactions);
        UUID report = UUID.randomUUID();
        UUID call = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        var fence = new AgentRunFence(run, AgentRunRef.INSTANCE_SCOPE, 3);
        var route = new ProxyRouting(
                "draft",
                "openai-completions",
                "https://model.example",
                UsageFundingSource.INSTANCE,
                3L,
                4L,
                null,
                new ProxyRouting.BilledAttempt(UsageSourceType.PRODUCT_FEEDBACK_DRAFT, report, 2, run, null, 3),
                Instant.now().plusSeconds(30),
                100L);
        when(drafts.admitCall(report, 2, 4L)).thenReturn(true);
        when(admission.admitInstance(4L))
                .thenReturn(new LlmAdmissionService.InstanceCharge("draft-model", LlmPriceSnapshot.unpricedInstance()));
        when(recorder.open(any())).thenReturn(call);
        assertThat(ledger.open(route, new ProxyCallLedger.Bound(80, 100L), "draft-model", null))
                .isEqualTo(call);
        var opened = ArgumentCaptor.forClass(LlmUsageRecorder.OpenCall.class);
        verify(recorder).open(opened.capture());
        assertThat(opened.getValue().workspaceId()).isNull();
        assertThat(opened.getValue().runId()).isEqualTo(run);
        assertThat(opened.getValue().leaseEpoch()).isEqualTo(3);
        assertThat(opened.getValue().model()).isEqualTo("draft-model");
        verify(runs).requireCurrent(fence);
        var usage = new LlmUsageRecorder.CallUsage(10, 5, 3, 2, 0, 0);
        var failure = new ModelCallFailure("upstream_stalled", null);
        when(recorder.complete(call, null, usage, failure)).thenReturn(true, false);
        ledger.complete(route, call, usage, failure);
        ledger.complete(route, call, usage, failure);
        verify(drafts).count(report, 15, 5);
        doThrow(new StaleRunException(fence)).when(runs).requireCurrent(fence);
        assertThatThrownBy(() -> ledger.open(route, new ProxyCallLedger.Bound(80, 100L), "draft-model", null))
                .isInstanceOf(ProxyCallLedger.ClaimLost.class);
        verify(recorder, times(1)).open(any());
    }
}
