package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelUnavailableException;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.usage.LlmAdmissionService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDraftCalls;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

class ProbeCallLedgerTest extends BaseUnitTest {
    @Mock
    private LlmUsageRecorder recorder;

    @Mock
    private LlmAdmissionService admission;

    @Mock
    private AgentRuns runs;

    @Mock
    private ProductFeedbackDraftCalls drafts;

    private ProxyCallLedger ledger;
    private final LlmModelResolver.ConnectionRef model =
            new LlmModelResolver.ConnectionRef(UsageFundingSource.INSTANCE, 3L, 4L, null);

    @BeforeEach
    void setUp() {
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        ledger = new ProxyCallLedger(
                admission,
                drafts,
                recorder,
                runs,
                mock(ReviewJobRepository.class),
                mock(MentorTurnRepository.class),
                mock(ProxyBudgetGate.class),
                new ObjectMapper(),
                new SimpleMeterRegistry(),
                transactions);
        when(admission.admitProbe(model))
                .thenReturn(new LlmAdmissionService.ProbeCharge("upstream-model", LlmPriceSnapshot.unpricedInstance()));
    }

    private ProxyRouting route() {
        return new ProxyRouting(
                "model-capability-probe",
                "openai-completions",
                "",
                model.scope(),
                model.connectionId(),
                model.modelId(),
                null,
                new ProxyRouting.BilledAttempt(UsageSourceType.PROBE, UUID.randomUUID(), 0, null),
                Instant.now().plusSeconds(15),
                64L);
    }

    @Test
    void shouldUseTheDatabaseRecoveryDeadlineAndExactCatalogIdentityWithoutARunOrAction() {
        Instant databaseDeadline = Instant.parse("2026-01-01T00:00:15Z");
        when(recorder.recoveryDeadline(LlmModelProbeGateway.CALL_TIMEOUT)).thenReturn(databaseDeadline);
        UUID call = UUID.randomUUID();
        when(recorder.open(any())).thenReturn(call);
        assertThat(ledger.open(route(), new ProxyCallLedger.Bound(80, 64L), "upstream-model"))
                .isEqualTo(call);
        var opened = ArgumentCaptor.forClass(LlmUsageRecorder.OpenCall.class);
        verify(recorder).open(opened.capture());
        assertThat(opened.getValue().settleAfter()).isEqualTo(databaseDeadline);
        assertThat(opened.getValue().appliedInstanceModelId()).isEqualTo(4L);
        assertThat(opened.getValue().workspaceId()).isNull();
        assertThat(opened.getValue().runId()).isNull();
        assertThat(opened.getValue().leaseEpoch()).isNull();
        assertThat(opened.getValue().actionId()).isNull();
        verifyNoInteractions(runs, drafts);
    }

    @Test
    void shouldRefuseBeforeOpeningTheCallWhenTheModelChangedAfterItsCredentialWasRead() {
        assertThatThrownBy(() -> ledger.open(route(), new ProxyCallLedger.Bound(80, 64L), "old-model"))
                .isInstanceOf(LlmModelUnavailableException.class);
        verifyNoInteractions(recorder, runs, drafts);
    }
}
