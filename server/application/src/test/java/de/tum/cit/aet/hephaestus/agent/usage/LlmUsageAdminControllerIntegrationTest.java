package de.tum.cit.aet.hephaestus.agent.usage;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.usage.fx.FxRate;
import de.tum.cit.aet.hephaestus.agent.usage.fx.FxRateRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@Tag("integration")
class LlmUsageAdminControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String ADMIN_TOKEN = "mock-jwt-token-for-admin-user";
    private static final String MENTOR_TOKEN = "mock-jwt-token-for-mentor-user";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private LlmUsageEventRepository usageRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private FxRateRepository fxRateRepository;

    private Workspace setupWorkspace(String slug) {
        User owner = persistUser(slug + "-owner");
        return createWorkspace(slug, "Admin usage " + slug, slug + "-org", AccountType.ORG, owner);
    }

    private void seedEvent(Workspace workspace, String cost) {
        seedEvent(workspace, cost, FundingSource.INSTANCE, PricingState.PRICED);
    }

    private void seedEvent(
            Workspace workspace,
            @org.jspecify.annotations.Nullable String cost,
            FundingSource funding,
            PricingState pricing) {
        LlmUsageEvent event = new LlmUsageEvent();
        event.setId(UUID.randomUUID());
        event.setWorkspace(workspace);
        event.setJobType(LlmUsageJobType.PULL_REQUEST_REVIEW);
        event.setSourceType(LlmUsageSourceType.AGENT_JOB);
        event.setSourceId(UUID.randomUUID());
        event.setCostUsd(cost == null ? null : new BigDecimal(cost));
        // Budgeted spend only counts PRICED + INSTANCE-funded rows — both are the
        // entity defaults, but set them explicitly so this fixture keeps meaning that if the
        // defaults ever change.
        event.setPricingState(pricing);
        event.setFundingSource(funding);
        event.setOccurredAt(Instant.now());
        usageRepository.save(event);
    }

    private AdminWorkspaceLlmUsageDTO rollupFor(Workspace workspace) {
        AdminLlmUsageReportDTO report = webTestClient
                .get()
                .uri("/admin/llm/usage")
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(AdminLlmUsageReportDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(report).isNotNull();
        return report.workspaces().stream()
                .filter(r -> r.workspaceSlug().equals(workspace.getWorkspaceSlug()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void adminSeesPerWorkspaceSpendIncludingZeroSpendWorkspaces() {
        Workspace spender = setupWorkspace("adm-spender");
        spender.setMonthlyLlmBudgetUsd(new BigDecimal("2.00"));
        workspaceRepository.save(spender);
        Workspace idle = setupWorkspace("adm-idle");
        seedEvent(spender, "3.00");

        var spenderRow = rollupFor(spender);
        assertThat(spenderRow.instanceTotalCostUsd()).isEqualByComparingTo("3.00");
        assertThat(spenderRow.ownProviderTotalCostUsd()).isEqualByComparingTo("0");
        assertThat(spenderRow.instanceMonthlyBudgetUsd()).isEqualByComparingTo("2.00");
        assertThat(spenderRow.instanceBudgetVerdict()).isEqualTo(LlmBudgetVerdict.EXHAUSTED);
        assertThat(spenderRow.instancePaused()).isTrue();
        var idleRow = rollupFor(idle);
        assertThat(idleRow.instanceTotalCostUsd()).isEqualByComparingTo("0");
        assertThat(idleRow.instanceBudgetVerdict()).isEqualTo(LlmBudgetVerdict.WITHIN);
        assertThat(idleRow.instancePaused()).isFalse();
    }

    @Test
    void adminSeesTheWorkspacesOwnCapAndVerdictWithoutItAffectingTheInstanceVerdict() {
        Workspace workspace = setupWorkspace("adm-byo");
        workspace.setMonthlyLlmBudgetUsd(new BigDecimal("100.00")); // host cap: nowhere near reached
        workspace.setMonthlyByoLlmBudgetUsd(new BigDecimal("5.00")); // workspace's own cap: reached
        workspaceRepository.save(workspace);
        seedEvent(workspace, "1.00");
        seedEvent(workspace, "5.00", FundingSource.WORKSPACE, PricingState.PRICED);

        var row = rollupFor(workspace);

        assertThat(row.instanceMonthlyBudgetUsd()).isEqualByComparingTo("100.00");
        assertThat(row.ownProviderMonthlyBudgetUsd()).isEqualByComparingTo("5.00");
        assertThat(row.instanceTotalCostUsd()).isEqualByComparingTo("1.00");
        assertThat(row.ownProviderTotalCostUsd()).isEqualByComparingTo("5.00");
        assertThat(row.instanceBudgetVerdict()).isEqualTo(LlmBudgetVerdict.WITHIN);
        assertThat(row.ownProviderBudgetVerdict()).isEqualTo(LlmBudgetVerdict.EXHAUSTED);
        assertThat(row.instancePaused()).isFalse();
        assertThat(row.ownProviderPaused()).isTrue();
    }

    /** Each verdict is only ever UNVERIFIABLE from a blind spot its own owner can clear. */
    @Test
    void anUnpricedOwnProviderEventLeavesTheInstanceVerdictUntouched() {
        Workspace workspace = setupWorkspace("adm-byo-unpriced");
        workspace.setMonthlyLlmBudgetUsd(new BigDecimal("100.00"));
        workspace.setMonthlyByoLlmBudgetUsd(new BigDecimal("100.00"));
        workspaceRepository.save(workspace);
        seedEvent(workspace, "1.00");
        seedEvent(workspace, null, FundingSource.WORKSPACE, PricingState.UNPRICED);

        var row = rollupFor(workspace);

        assertThat(row.instanceBudgetVerdict()).isEqualTo(LlmBudgetVerdict.WITHIN);
        assertThat(row.ownProviderBudgetVerdict()).isEqualTo(LlmBudgetVerdict.UNVERIFIABLE);
        assertThat(row.instancePaused()).isFalse();
        assertThat(row.ownProviderPaused()).isTrue();
    }

    @Test
    void adminSetsAndClearsTheBudgetCap() {
        Workspace workspace = setupWorkspace("adm-budget");

        webTestClient
                .put()
                .uri("/admin/workspaces/{slug}/llm/budget", workspace.getWorkspaceSlug())
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("monthlyBudgetUsd", "25.00"))
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);
        assertThat(workspaceRepository.findById(workspace.getId()).orElseThrow().getMonthlyLlmBudgetUsd())
                .isEqualByComparingTo("25.00");

        webTestClient
                .put()
                .uri("/admin/workspaces/{slug}/llm/budget", workspace.getWorkspaceSlug())
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);
        assertThat(workspaceRepository.findById(workspace.getId()).orElseThrow().getMonthlyLlmBudgetUsd())
                .isNull();
    }

    /**
     * A typo'd slug that returned 204 would tell an operator their cap is in place when no workspace
     * is capped at all.
     */
    @Test
    void settingTheCapOnAnUnknownWorkspaceSlugIs404() {
        webTestClient
                .put()
                .uri("/admin/workspaces/{slug}/llm/budget", "adm-no-such-workspace")
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("monthlyBudgetUsd", "25.00"))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    void negativeBudgetIsRejectedWith400() {
        Workspace workspace = setupWorkspace("adm-negative");

        webTestClient
                .put()
                .uri("/admin/workspaces/{slug}/llm/budget", workspace.getWorkspaceSlug())
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("monthlyBudgetUsd", "-1.00"))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
    }

    /**
     * The fixture stores a perfectly fresh rate first, so the only thing that can be suppressing
     * {@code fx} is the unset property.
     */
    @Test
    void rollupOmitsFxEntirelyWhenNoDisplayCurrencyConfigured() {
        Workspace workspace = setupWorkspace("adm-no-fx");
        seedEvent(workspace, "3.00");
        FxRate rate = new FxRate();
        rate.setRateDate(LocalDate.now(ZoneOffset.UTC));
        rate.setUsdPerEur(new BigDecimal("1.1377"));
        rate.setFetchedAt(Instant.now());
        fxRateRepository.save(rate);

        byte[] body = webTestClient
                .get()
                .uri("/admin/llm/usage")
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.fx")
                .doesNotExist()
                .jsonPath("$.workspaces[0].fx")
                .doesNotExist()
                .returnResult()
                .getResponseBody();

        assertThat(new String(body, StandardCharsets.UTF_8)).doesNotContain("\"fx\"");
    }

    @Test
    void nonAdminIsForbidden() {
        webTestClient
                .get()
                .uri("/admin/llm/usage")
                .headers(h -> h.setBearerAuth(MENTOR_TOKEN))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    void anonymousIsUnauthorized() {
        // 401, not 403: an unauthenticated caller must be told to authenticate. The two are answered
        // by different layers (the entry point vs. @PreAuthorize), so passing the 403 case above says
        // nothing about this one.
        webTestClient
                .get()
                .uri("/admin/llm/usage")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
    }

    @Test
    void settingAWorkspacesBudgetIsRefusedForBothANonAdminAndAnAnonymousCaller() {
        webTestClient
                .put()
                .uri("/admin/workspaces/{slug}/llm/budget", "any-workspace")
                .headers(h -> h.setBearerAuth(MENTOR_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("monthlyBudgetUsd", 1))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);

        // 403, not the 401 the anonymous *read* above returns: a state-changing request with no
        // `Authorization: Bearer` header is cookie-shaped, so SecurityConfig#requiresCsrf refuses it
        // at the CSRF filter before authentication ever runs. Asserting 401 here would invite someone
        // to "fix" the app by exempting this mutation from CSRF. Either way the handler is unreachable.
        webTestClient
                .put()
                .uri("/admin/workspaces/{slug}/llm/budget", "any-workspace")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("monthlyBudgetUsd", 1))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }
}
