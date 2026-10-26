package de.tum.cit.aet.hephaestus.agent.usage;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.LlmProperties;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.usage.fx.FxRate;
import de.tum.cit.aet.hephaestus.agent.usage.fx.FxRateLookup;
import de.tum.cit.aet.hephaestus.agent.usage.fx.FxRateRepository;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

class LlmUsageFxDisplayIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final YearMonth CURRENT = YearMonth.now(ZoneOffset.UTC);

    @Autowired
    private LlmUsageEventRepository usageRepository;

    @Autowired
    private FxRateRepository fxRateRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private LlmBudgetService budgetService;

    @Autowired
    private ConfigAuditPort configAudit;

    @Autowired
    private ReviewJobRepository jobRepository;

    @Autowired
    private Clock clock;

    @Autowired
    private ObjectMapper objectMapper;

    private LlmUsageService usageService;
    private LlmUsageAdminService adminService;

    @BeforeEach
    void setUpServices() {
        LlmProperties properties = new LlmProperties(
                "EUR",
                new LlmProperties.Egress(false, List.of(), false),
                new LlmProperties.Fx(LlmProperties.ECB_DAILY_URL));
        FxRateLookup rates = new FxRateLookup(fxRateRepository, clock, properties);
        usageService = new LlmUsageService(
                usageRepository, workspaceRepository, budgetService, configAudit, jobRepository, rates);
        adminService =
                new LlmUsageAdminService(usageRepository, workspaceRepository, configAudit, jobRepository, rates);
    }

    @Test
    void shouldIncludeEurRateWithoutConvertingWorkspaceCosts() {
        Workspace workspace = workspace("fx-workspace");
        seedEvent(workspace, "10.00");
        LocalDate today = seedTodaysRate();

        WorkspaceLlmUsageReportDTO report = usageService.getWorkspaceReport(workspace.getId(), CURRENT);

        assertThat(report.fx()).isNotNull();
        assertThat(report.fx().currencyCode()).isEqualTo("EUR");
        assertThat(report.fx().ratePerUsd()).isEqualByComparingTo("0.878966");
        assertThat(report.fx().rateDate()).isEqualTo(today);
        assertThat(report.instanceTotalCostUsd()).isEqualByComparingTo("10.00");
    }

    @Test
    void shouldSerializeRateOnceOnAdminEnvelope() throws Exception {
        seedEvent(workspace("fx-admin-a"), "1.00");
        seedEvent(workspace("fx-admin-b"), "2.00");
        LocalDate today = seedTodaysRate();

        AdminLlmUsageReportDTO report = adminService.getReport(CURRENT);
        String json = objectMapper.writeValueAsString(report);

        assertThat(report.month()).isEqualTo(CURRENT.toString());
        assertThat(report.workspaces()).hasSize(2);
        assertThat(report.fx()).isNotNull();
        assertThat(report.fx().rateDate()).isEqualTo(today);
        assertThat(report.fx().source()).isEqualTo("ECB");
        assertThat(json.split("\\\"fx\\\"", -1)).hasSize(2);
    }

    @Test
    void shouldJudgeBudgetInUsdWhenDisplayRateWouldUndercutIt() {
        Workspace workspace = workspace("fx-verdict-usd");
        workspace.setMonthlyLlmBudgetUsd(new BigDecimal("8.00"));
        workspaceRepository.save(workspace);
        seedEvent(workspace, "9.00");
        seedTodaysRate();

        WorkspaceLlmUsageReportDTO report = usageService.getWorkspaceReport(workspace.getId(), CURRENT);

        assertThat(report.fx()).isNotNull();
        assertThat(report.instanceTotalCostUsd()).isEqualByComparingTo("9.00");
        assertThat(report.instanceBudgetVerdict()).isEqualTo(LlmBudgetVerdict.EXHAUSTED);
        assertThat(report.instancePaused()).isTrue();
    }

    @Test
    void shouldOmitRateWhenStoredRateIsStale() {
        Workspace workspace = workspace("fx-stale");
        seedEvent(workspace, "10.00");
        FxRate stale = new FxRate();
        stale.setRateDate(LocalDate.now(ZoneOffset.UTC).minusDays(8));
        stale.setUsdPerEur(new BigDecimal("1.1377"));
        stale.setFetchedAt(Instant.now());
        fxRateRepository.save(stale);

        assertThat(usageService.getWorkspaceReport(workspace.getId(), CURRENT).fx())
                .isNull();
    }

    private Workspace workspace(String slug) {
        User owner = persistUser(slug + "-owner");
        Workspace workspace = createWorkspace(slug, "Fx " + slug, slug + "-org", AccountType.ORG, owner);
        ensureAdminMembership(workspace);
        return workspace;
    }

    private void seedEvent(Workspace workspace, String cost) {
        LlmUsageEvent event = new LlmUsageEvent();
        event.setId(UUID.randomUUID());
        event.setWorkspaceId(workspace.getId());
        event.setJobType("PRACTICE_REVIEW");
        event.setSourceType(UsageSourceType.REVIEW_JOB);
        event.setSourceId(UUID.randomUUID());
        event.setModel("claude-sonnet-5");
        event.setCostUsd(new BigDecimal(cost));
        event.setPricingState(UsagePricingState.PRICED);
        event.setFundingSource(UsageFundingSource.INSTANCE);
        event.setOccurredAt(
                CURRENT.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600));
        usageRepository.save(event);
    }

    private LocalDate seedTodaysRate() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        FxRate rate = new FxRate();
        rate.setRateDate(today);
        rate.setUsdPerEur(new BigDecimal("1.1377"));
        rate.setFetchedAt(Instant.now());
        fxRateRepository.save(rate);
        return today;
    }
}
