package de.tum.cit.aet.hephaestus.workspace.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class WorkspaceAccessRetentionTest {
    private static final Instant END = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void shouldEraseAtTheRetentionBoundaryButNotBeforeIt() {
        var request = request();
        request.setStatus(WorkspaceAccessRequest.Status.APPROVED);
        request.setApprovedDetails(new WorkspaceAccessDetails(1L, List.of(), END));
        var cutoff = END.plusSeconds(30L * 86400);
        assertThat(WorkspaceAccessRetention.isDue(request, cutoff.minusNanos(1)))
                .isFalse();
        assertThat(WorkspaceAccessRetention.isDue(request, cutoff)).isTrue();
    }

    @Test
    void shouldKeepUnresolvedOrUndatedRequestsRegardlessOfTheirAge() {
        var request = request();
        var later = END.plusSeconds(3650L * 86400);
        assertThat(WorkspaceAccessRetention.isDue(request, later)).isFalse();
        request.setStatus(WorkspaceAccessRequest.Status.CHANGES_REQUESTED);
        request.setDecidedAt(END);
        assertThat(WorkspaceAccessRetention.isDue(request, later)).isFalse();
        request.setStatus(WorkspaceAccessRequest.Status.APPROVED);
        assertThat(WorkspaceAccessRetention.isDue(request, later)).isFalse();
        request.setStatus(WorkspaceAccessRequest.Status.CANCELLED);
        request.setDecidedAt(null);
        assertThat(WorkspaceAccessRetention.isDue(request, later)).isFalse();
    }

    @Test
    void shouldUseTheTerminalTransitionRatherThanTheOriginalSubmissionForRetention() {
        var request = request();
        request.setSubmittedAt(END.minusSeconds(365L * 86400));
        request.setStatus(WorkspaceAccessRequest.Status.REJECTED);
        request.setDecidedAt(END);
        assertThat(WorkspaceAccessRetention.isDue(request, END.plusSeconds(86400)))
                .isFalse();
        assertThat(WorkspaceAccessRetention.isDue(request, END.plusSeconds(30L * 86400)))
                .isTrue();
    }

    private static WorkspaceAccessRequest request() {
        var request = new WorkspaceAccessRequest();
        request.setPolicySnapshot(new WorkspaceAccessPolicySettings(
                "github",
                "Welcome",
                "I agree",
                List.of(),
                List.of(),
                1L,
                List.of(),
                90,
                14,
                "admins@example.test",
                30));
        return request;
    }
}
