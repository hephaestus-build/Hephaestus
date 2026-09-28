package de.tum.cit.aet.hephaestus.agent.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.backfill.dto.CreateReviewBackfillRunRequestDTO;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** The preflight: what an admin is told before anything is spent, and what is refused outright. */
@DisplayName("Review backfill preflight")
class ReviewBackfillServiceTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 3L;
    private static final Instant FROM = Instant.parse("2026-07-08T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-07T00:00:00Z");

    @Mock
    private ReviewBackfillRunRepository runRepository;

    @Mock
    private ReviewBackfillScopeRepository scopeRepository;

    @Mock
    private ReviewBackfillCostEstimator costEstimator;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private ConfigAuditPort configAudit;

    private final ReviewBackfillProperties properties =
            new ReviewBackfillProperties(25, Duration.ofDays(400), 5000, Duration.ofDays(90));

    @BeforeEach
    void authenticate() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("77")
                .claim("scope", "read")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private ReviewBackfillService service() {
        return new ReviewBackfillService(
                runRepository, scopeRepository, costEstimator, workspaceRepository, properties, configAudit);
    }

    @Test
    void thePreflightCostsTheScopeAndSubmitsNothing() {
        stubScope(120);
        when(costEstimator.estimateTotalUsd(anyLong(), any(), anyInt())).thenReturn(new BigDecimal("14.4000"));
        when(runRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var run = service().preflight(context(), request());

        assertThat(run.status()).isEqualTo(ReviewBackfillStatus.AWAITING_CONFIRMATION);
        assertThat(run.estimatedArtifacts()).isEqualTo(120);
        assertThat(run.estimatedCostUsd()).isEqualByComparingTo("14.40");
        assertThat(run.confirmedByAccountId()).isNull();
        assertThat(run.requestedByAccountId()).isEqualTo(77L);
    }

    /**
     * A fresh workspace — the one most likely to want a baseline — has no priced history. An unknown cost
     * stays unknown; rendering it as zero would read as "this is free" on the one screen where that
     * matters most.
     */
    @Test
    void anUnpriceableCampaignReportsNoEstimateRatherThanZero() {
        stubScope(40);
        when(costEstimator.estimateTotalUsd(anyLong(), any(), anyInt())).thenReturn(null);
        when(runRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        assertThat(service().preflight(context(), request()).estimatedCostUsd()).isNull();
    }

    @Test
    void anInvertedWindowIsRefused() {
        assertThatThrownBy(() -> service()
                        .preflight(
                                context(), new CreateReviewBackfillRunRequestDTO(ArtifactKinds.PULL_REQUEST, TO, FROM)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must start before it ends");
        verify(runRepository, never()).save(any());
    }

    /** Told at preflight, with the count, so the admin narrows the window instead of discovering it later. */
    @Test
    void aScopeOverTheLimitIsRefusedWithItsSize() {
        stubScope(9001);

        assertThatThrownBy(() -> service().preflight(context(), request()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("9001")
                .hasMessageContaining("5000");
        verify(runRepository, never()).save(any());
    }

    @Test
    void aWindowLongerThanTheLimitIsRefused() {
        assertThatThrownBy(() -> service()
                        .preflight(
                                context(),
                                new CreateReviewBackfillRunRequestDTO(
                                        ArtifactKinds.PULL_REQUEST, TO.minus(Duration.ofDays(500)), TO)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Two overlapping campaigns each read the other's ledger rows as "already covered", so neither covers
     * the scope its estimate described.
     */
    @Test
    void aSecondCampaignIsRefusedWhileOneIsUnderWay() {
        when(runRepository.existsByWorkspaceIdAndStatusIn(anyLong(), any())).thenReturn(true);

        assertThatThrownBy(() -> service().preflight(context(), request()))
                .isInstanceOf(ReviewBackfillConflictException.class);
    }

    /** An estimate nobody acted on is a draft; a corrected one supersedes it rather than being blocked. */
    @Test
    void aNewEstimateSupersedesAnUnconfirmedOne() {
        ReviewBackfillRun stale = new ReviewBackfillRun();
        stale.setStatus(ReviewBackfillStatus.AWAITING_CONFIRMATION);
        when(runRepository.findByWorkspaceIdOrderByCreatedAtDesc(anyLong(), any()))
                .thenReturn(List.of(stale));
        stubScope(10);
        when(runRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service().preflight(context(), request());

        assertThat(stale.getStatus()).isEqualTo(ReviewBackfillStatus.CANCELLED);
    }

    @Test
    void aConversationThreadCannotBeBackfilled() {
        assertThatThrownBy(() -> ReviewBackfillService.jobTypeFor(ArtifactKinds.CONVERSATION_THREAD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chat.conversation_thread");
    }

    private void stubScope(long count) {
        when(scopeRepository.countPullRequests(anyLong(), any(), any())).thenReturn(count);
        Workspace workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        Mockito.lenient().when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace));
    }

    private CreateReviewBackfillRunRequestDTO request() {
        return new CreateReviewBackfillRunRequestDTO(ArtifactKinds.PULL_REQUEST, FROM, TO);
    }

    private WorkspaceContext context() {
        return new WorkspaceContext(WORKSPACE_ID, "acme", "Acme", null, null, false, Set.of());
    }
}
