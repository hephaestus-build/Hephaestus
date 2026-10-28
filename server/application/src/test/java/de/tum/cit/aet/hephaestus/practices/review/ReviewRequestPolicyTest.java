package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import de.tum.cit.aet.hephaestus.practices.PracticeItemOptions;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class ReviewRequestPolicyTest {
    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final ReviewRepository reviews = mock(ReviewRepository.class);
    private final PracticeReviewProperties properties = mock(PracticeReviewProperties.class);
    private final PracticeReviewCoverageService coverage = mock(PracticeReviewCoverageService.class);
    private final ReviewGate gate =
            new ReviewGate(mock(PracticeRepository.class), mock(PracticeItemOptions.class), new JsonMapper());
    private final ReviewRequestPolicy policy = new ReviewRequestPolicy(
            workspaces,
            reviews,
            properties,
            coverage,
            Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC),
            gate);
    private final Workspace workspace = new Workspace();

    @BeforeEach
    void setUp() {
        workspace.setId(1L);
        workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        workspace.getFeatures().setPracticesEnabled(true);
        when(workspaces.findByIdForUpdate(1L)).thenReturn(Optional.of(workspace));
    }

    @Test
    void shouldRefuseDisabledRequestsBeforeConsentAndQuotaChecks() {
        workspace.getFeatures().setPracticeReviewManualTriggerEnabled(false);
        assertThat(policy.refusal(1, 2, 3)).contains(ReviewRequestReason.GATE_WITHHELD);
        verifyNoInteractions(coverage, reviews);
    }

    @Test
    void shouldKeepConsentRequiredWhenRequestsAreEnabled() {
        when(coverage.eligibleAccountIds(1)).thenReturn(Set.of());
        assertThat(policy.refusal(1, 2, 3)).contains(ReviewRequestReason.MEMBER_AI_DECLINED);
        verifyNoInteractions(reviews);
    }

    @Test
    void shouldKeepTheRequesterQuota() {
        when(coverage.eligibleAccountIds(1)).thenReturn(Set.of(3L));
        when(properties.maxRequestsPerRequesterPerHour()).thenReturn(1);
        when(reviews.countByWorkspaceIdAndRequestedByIdAndOriginAndCreatedAtGreaterThanEqual(
                        anyLong(), anyLong(), any(ReviewOrigin.class), any(Instant.class)))
                .thenReturn(1L);
        assertThat(policy.refusal(1, 2, 3)).contains(ReviewRequestReason.REQUESTER_QUOTA_EXHAUSTED);
    }

    @Test
    void shouldKeepTheItemCooldown() {
        when(coverage.eligibleAccountIds(1)).thenReturn(Set.of(3L));
        when(properties.cooldownMinutes()).thenReturn(15);
        when(reviews.existsByWorkspaceIdAndItemIdAndOriginAndCreatedAtGreaterThanEqual(
                        anyLong(), anyLong(), any(ReviewOrigin.class), any(Instant.class)))
                .thenReturn(true);
        assertThat(policy.refusal(1, 2, 3)).contains(ReviewRequestReason.REQUEST_COOLDOWN_ACTIVE);
    }

    @Test
    void shouldKeepConcurrentDuplicateProtectionAndAllowAnEligibleRequest() {
        when(coverage.eligibleAccountIds(1)).thenReturn(Set.of(3L));
        assertThat(policy.refusal(1, 2, 3)).isEmpty();
        when(reviews.existsByWorkspaceIdAndItemIdAndStatusIn(1, 2, List.of(ReviewStatus.QUEUED, ReviewStatus.RUNNING)))
                .thenReturn(true);
        assertThat(policy.refusal(1, 2, 3)).contains(ReviewRequestReason.CONCURRENT_DUPLICATE);
    }
}
