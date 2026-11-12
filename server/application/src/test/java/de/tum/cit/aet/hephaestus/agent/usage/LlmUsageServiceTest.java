package de.tum.cit.aet.hephaestus.agent.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.job.ReviewRunKind;
import de.tum.cit.aet.hephaestus.agent.usage.fx.FxRateLookup;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class LlmUsageServiceTest extends BaseUnitTest {
    private static final long WORKSPACE_ID = 42L;

    @Mock
    private LlmUsageEventRepository usageRepository;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private LlmBudgetService budgetService;

    @Mock
    private FxRateLookup fxRateLookup;

    /**
     * A call that holds a bound of $0.50 ends at $0.20 while the report reads. The cap sum that a later read gives
     * already holds the measured $0.20; less the bound that an earlier read gave, it would show $0.70 as confirmed.
     */
    @Test
    void shouldReportConfirmedSpendFromTheReadThatGaveTheBoundsWhenACallEndsBetweenReads() {
        Workspace workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        workspace.setMonthlyLlmBudgetUsd(new BigDecimal("1.40"));
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace));
        var spend = mock(LlmUsageEventRepository.MonthSpend.class);
        when(spend.getInstanceConfirmedUsd()).thenReturn(new BigDecimal("1.00"));
        when(spend.getInstanceCalls()).thenReturn(1L);
        when(spend.getInstanceUpperBoundUsd()).thenReturn(new BigDecimal("0.50"));
        when(spend.getOwnProviderConfirmedUsd()).thenReturn(BigDecimal.ZERO);
        when(spend.getOwnProviderUpperBoundUsd()).thenReturn(BigDecimal.ZERO);
        when(usageRepository.aggregateSpend(eq(WORKSPACE_ID), any(Instant.class), any(Instant.class)))
                .thenReturn(spend);
        lenient()
                .when(usageRepository.sumCost(eq(WORKSPACE_ID), any(Instant.class), any(Instant.class)))
                .thenReturn(new BigDecimal("1.20"));
        when(budgetService.decide(WORKSPACE_ID)).thenReturn(LlmBudgetDecision.ALLOWED);
        var service = new LlmUsageService(
                usageRepository,
                workspaceRepository,
                budgetService,
                mock(ConfigAuditPort.class),
                mock(ReviewRunKind.class),
                fxRateLookup);

        var report = service.getWorkspaceReport(WORKSPACE_ID, YearMonth.now(ZoneOffset.UTC));

        assertThat(report.instanceTotalCostUsd()).isEqualByComparingTo("1.00");
        assertThat(report.instanceUnmeasured().upperBoundUsd()).isEqualByComparingTo("0.50");
        assertThat(report.instanceBudgetVerdict())
                .as("the cap compares the confirmed spend and the bounds of the same read: $1.50")
                .isEqualTo(LlmBudgetVerdict.EXHAUSTED);
    }
}
