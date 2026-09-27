package de.tum.cit.aet.hephaestus.agent.backfill;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.backfill.dto.CreateReviewSweepScheduleRequestDTO;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;

/**
 * What a workspace is allowed to ask a recurring sweep to do.
 *
 * <p>One rule carries the whole design: a sweep may look back at most twice its cadence, never more than
 * a week, since a longer window filed in the same population as live-triggered reviews would show a
 * trend line improving for no reason.
 */
@DisplayName("Review sweep schedule service")
class ReviewSweepScheduleServiceTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 5L;

    @Mock
    private ReviewSweepScheduleRepository scheduleRepository;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private ConfigAuditPort configAudit;

    private ReviewSweepScheduleService service() {
        return new ReviewSweepScheduleService(scheduleRepository, workspaceRepository, configAudit);
    }

    @ParameterizedTest
    @CsvSource({"DAILY, 3", "DAILY, 7", "WEEKLY, 8", "WEEKLY, 30"})
    void aWindowLongerThanTheCadenceAllowsIsRefusedBeforeAnythingIsWritten(String cadence, int lookbackDays) {
        assertThatThrownBy(() -> service()
                        .create(
                                context(),
                                request(ArtifactKinds.PULL_REQUEST, ReviewSweepCadence.valueOf(cadence), lookbackDays)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backfill campaign");

        verify(scheduleRepository, never()).save(any());
    }

    @ParameterizedTest
    @CsvSource({"DAILY, 1", "DAILY, 2", "WEEKLY, 1", "WEEKLY, 7"})
    void aWindowWithinTheCeilingGetsPastValidation(String cadence, int lookbackDays) {
        when(scheduleRepository.existsByWorkspaceIdAndArtifactKind(anyLong(), anyString()))
                .thenReturn(true);

        // A conflict exception, raised strictly after the window check, is what proves the window passed.
        assertThatThrownBy(() -> service()
                        .create(
                                context(),
                                request(ArtifactKinds.PULL_REQUEST, ReviewSweepCadence.valueOf(cadence), lookbackDays)))
                .isInstanceOf(ReviewSweepScheduleConflictException.class);
    }

    /**
     * A conversation thread has no mirrored corpus a campaign could walk, so a schedule for one would
     * quietly sweep nothing.
     */
    @Test
    void aKindNoCampaignCanEnumerateIsRefusedByName() {
        assertThatThrownBy(() -> service()
                        .create(context(), request(ArtifactKinds.CONVERSATION_THREAD, ReviewSweepCadence.DAILY, 1)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(scheduleRepository, never()).save(any());
    }

    /**
     * A second schedule for the same kind could never run: only one campaign is under way per workspace
     * at a time, so the loser would look enabled for ever while sweeping nothing.
     */
    @Test
    void aSecondScheduleForTheSameKindOfWorkIsRefused() {
        when(scheduleRepository.existsByWorkspaceIdAndArtifactKind(anyLong(), anyString()))
                .thenReturn(true);

        assertThatThrownBy(() ->
                        service().create(context(), request(ArtifactKinds.PULL_REQUEST, ReviewSweepCadence.DAILY, 2)))
                .isInstanceOf(ReviewSweepScheduleConflictException.class);

        verify(scheduleRepository, never()).save(any());
    }

    private static CreateReviewSweepScheduleRequestDTO request(
            ArtifactKind kind, ReviewSweepCadence cadence, int lookbackDays) {
        return new CreateReviewSweepScheduleRequestDTO(kind, cadence, lookbackDays);
    }

    private static WorkspaceContext context() {
        return new WorkspaceContext(WORKSPACE_ID, "acme", "Acme", null, null, false, Set.of());
    }
}
