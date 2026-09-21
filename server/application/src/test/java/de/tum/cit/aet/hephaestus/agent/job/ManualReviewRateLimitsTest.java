package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignalRepository;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

/**
 * How often a review may be asked for by hand. Both limits exist because the workspace cooldown already
 * in the system is keyed on an idempotency key whose phase segment is the trigger signal, and a manual
 * request carries none.
 */
@Tag("unit")
@DisplayName("Limits on asking for a review")
class ManualReviewRateLimitsTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 3L;
    private static final long ARTIFACT_ID = 500L;
    private static final long REQUESTER_ID = 42L;

    @Mock
    private ArtifactSignalRepository signals;

    private Workspace workspace;

    @BeforeEach
    void setUp() {
        workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        lenient()
                .when(signals.existsManualRequestSince(anyLong(), anyString(), anyLong(), any()))
                .thenReturn(false);
        lenient().when(signals.countRequestsBySince(anyLong(), any(), any())).thenReturn(0L);
    }

    @Test
    void anAskAboutWorkNobodyHasAskedAboutIsLetThrough() {
        assertThat(refusalFrom(limits(15, 5))).isEmpty();
    }

    @Test
    void aSecondAskAboutTheSameWorkInsideTheCooldownIsRefused() {
        when(signals.existsManualRequestSince(anyLong(), anyString(), anyLong(), any()))
                .thenReturn(true);

        assertThat(refusalFrom(limits(15, 5))).contains(SignalStateReason.REQUEST_COOLDOWN_ACTIVE);
    }

    /** The workspace's cooldown override beats the fleet default; a second knob would let them disagree. */
    @Test
    void theArtifactWindowIsTheWorkspacesOwnCooldown() {
        workspace.getReviewSettings().applyPatch(null, 90);
        Instant before = Instant.now();

        refusalFrom(limits(15, 5));

        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(signals)
                .existsManualRequestSince(
                        org.mockito.ArgumentMatchers.eq(WORKSPACE_ID),
                        org.mockito.ArgumentMatchers.eq(ScmSignals.PULL_REQUEST.value()),
                        org.mockito.ArgumentMatchers.eq(ARTIFACT_ID),
                        since.capture());
        assertThat(since.getValue())
                .isBetween(before.minus(91, ChronoUnit.MINUTES), before.minus(89, ChronoUnit.MINUTES));
    }

    @Test
    void aCooldownOfZeroTurnsThePerArtifactLimitOff() {
        workspace.getReviewSettings().applyPatch(null, 0);

        assertThat(refusalFrom(limits(15, 5))).isEmpty();
        verify(signals, never()).existsManualRequestSince(anyLong(), anyString(), anyLong(), any());
    }

    @Test
    void aPersonWhoHasSpentTheHoursAllowanceIsRefused() {
        when(signals.countRequestsBySince(anyLong(), any(), any())).thenReturn(5L);

        assertThat(refusalFrom(limits(15, 5))).contains(SignalStateReason.REQUESTER_QUOTA_EXHAUSTED);
    }

    /** Checked first: the other order would tell someone over their allowance to just try again shortly. */
    @Test
    void theCooldownIsNotEvenConsultedOnceTheAllowanceIsSpent() {
        when(signals.countRequestsBySince(anyLong(), any(), any())).thenReturn(5L);

        assertThat(refusalFrom(limits(15, 5))).contains(SignalStateReason.REQUESTER_QUOTA_EXHAUSTED);
        verify(signals, never()).existsManualRequestSince(anyLong(), anyString(), anyLong(), any());
    }

    @Test
    void theAllowanceIsCountedOverTheLastHour() {
        Instant before = Instant.now();

        refusalFrom(limits(15, 5));

        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(signals).countRequestsBySince(org.mockito.ArgumentMatchers.eq(WORKSPACE_ID), any(), since.capture());
        assertThat(since.getValue())
                .isBetween(before.minus(61, ChronoUnit.MINUTES), before.minus(59, ChronoUnit.MINUTES));
    }

    @Test
    void anAllowanceOfZeroTurnsThePerPersonLimitOff() {
        assertThat(refusalFrom(limits(15, 0))).isEmpty();
        verify(signals, never()).countRequestsBySince(anyLong(), any(), any());
    }

    private ManualReviewRateLimits limits(int cooldownMinutes, int allowance) {
        return new ManualReviewRateLimits(
                signals, new PracticeReviewProperties(false, cooldownMinutes, allowance, false));
    }

    private Optional<SignalStateReason> refusalFrom(ManualReviewRateLimits limits) {
        return limits.refusalFor(workspace, ScmSignals.PULL_REQUEST, ARTIFACT_ID, List.of(REQUESTER_ID));
    }
}
