package de.tum.cit.aet.hephaestus.productfeedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.productfeedback.FilingLifecycle.Event;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** The whole table, written out once more by hand: a change to it must change this test on purpose. */
@Tag("unit")
class FilingLifecycleTest {
    private static final Map<FilingState, Map<Event, FilingState>> EXPECTED = Map.of(
            FilingState.DRAFT_QUEUED,
            Map.of(
                    Event.DRAFT_CLAIMED, FilingState.DRAFTING,
                    Event.DRAFT_ENDED, FilingState.ANSWERED,
                    Event.ROUTED_TO_HOLD, FilingState.HELD,
                    Event.ROUTED_TO_FILE, FilingState.QUEUED),
            FilingState.DRAFTING,
            Map.of(
                    Event.DRAFT_CLAIMED, FilingState.DRAFTING,
                    Event.DRAFT_ENDED, FilingState.ANSWERED,
                    Event.DRAFT_RETRIED, FilingState.DRAFT_QUEUED,
                    Event.ROUTED_TO_HOLD, FilingState.HELD,
                    Event.ROUTED_TO_FILE, FilingState.QUEUED),
            FilingState.ANSWERED,
            Map.of(Event.ROUTED_TO_HOLD, FilingState.HELD, Event.ROUTED_TO_FILE, FilingState.QUEUED),
            FilingState.HELD,
            Map.of(
                    Event.EDITED, FilingState.DRAFT_QUEUED,
                    Event.KEPT_PRIVATE, FilingState.QUEUED,
                    Event.REROUTED_PRIVATE, FilingState.QUEUED,
                    Event.FILING_CLAIMED, FilingState.FILING,
                    Event.SAVED, FilingState.SAVED,
                    Event.STOPPED, FilingState.STOPPED),
            FilingState.QUEUED,
            Map.of(
                    Event.REROUTED_PRIVATE, FilingState.QUEUED,
                    Event.FILING_CLAIMED, FilingState.FILING,
                    Event.SAVED, FilingState.SAVED,
                    Event.STOPPED, FilingState.STOPPED),
            FilingState.FILING,
            Map.of(
                    Event.FILING_CLAIMED, FilingState.FILING,
                    Event.FILING_REQUEUED, FilingState.QUEUED,
                    Event.FILED, FilingState.FILED,
                    Event.STOPPED, FilingState.STOPPED),
            FilingState.FILED,
            Map.of(Event.ROUTE_CHANGED_ON_GITHUB, FilingState.FILED),
            FilingState.SAVED,
            Map.of(Event.MOVED_DRAFTED, FilingState.DRAFT_QUEUED, Event.MOVED_UNDRAFTED, FilingState.ANSWERED),
            FilingState.STOPPED,
            Map.of(
                    Event.RETRIED_RECONCILE, FilingState.FILING,
                    Event.RETRIED_DRAFT, FilingState.DRAFT_QUEUED,
                    Event.RETRIED_ROUTE, FilingState.ANSWERED));

    @Test
    void shouldAllowExactlyTheListedTransitionsFromEveryState() {
        for (FilingState from : FilingState.values()) {
            for (Event event : Event.values()) {
                FilingState expected = EXPECTED.getOrDefault(from, Map.of()).get(event);
                assertThat(FilingLifecycle.next(from, event).orElse(null))
                        .as("%s from %s", event, from)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void shouldLeaveNoStateWithoutAWayOut() {
        for (FilingState state : FilingState.values())
            assertThat(FilingLifecycle.eventsFrom(state))
                    .as("exits of %s", state)
                    .isNotEmpty();
    }

    @Test
    void shouldReachEveryStateFromBothStartingPoints() {
        Set<FilingState> reached = EnumSet.of(FilingState.DRAFT_QUEUED, FilingState.SAVED);
        var open = new ArrayDeque<>(reached);
        while (!open.isEmpty()) {
            FilingState from = open.poll();
            for (Event event : FilingLifecycle.eventsFrom(from)) {
                FilingState to = FilingLifecycle.next(from, event).orElseThrow();
                if (reached.add(to)) open.add(to);
            }
        }
        assertThat(reached).containsExactlyInAnyOrder(FilingState.values());
    }

    @Test
    void shouldGiveEveryStateAMeaningForTheReporterAndTheAdmin() {
        for (FilingState state : FilingState.values()) {
            assertThat(FilingLifecycle.reporterState(state, FilingRoute.PRIVATE))
                    .isNotNull();
            assertThat(FilingLifecycle.waitsForAdmin(state))
                    .as("%s", state)
                    .isEqualTo(state == FilingState.SAVED || state == FilingState.STOPPED);
        }
    }

    @Test
    void shouldNotSayFilingForAReportBoundForTheAdmins() {
        assertThat(FilingLifecycle.reporterState(FilingState.QUEUED, FilingRoute.ADMINS))
                .isEqualTo(FilingDTOs.ReportState.SAVED_FOR_ADMINS);
        assertThat(FilingLifecycle.reporterState(FilingState.QUEUED, FilingRoute.PRIVATE))
                .isEqualTo(FilingDTOs.ReportState.FILING);
    }

    @Test
    void shouldRefuseAnIllegalEventAndChangeNothing() {
        var filing = new ProductFeedbackFiling(UUID.randomUUID(), FilingState.FILED, false, Instant.EPOCH);

        assertThatThrownBy(() -> filing.apply(Event.DRAFT_CLAIMED))
                .isInstanceOf(FilingLifecycle.IllegalTransition.class)
                .hasMessageContaining("DRAFT_CLAIMED")
                .hasMessageContaining("FILED");
        assertThat(filing.getState()).isEqualTo(FilingState.FILED);
    }

    @Test
    void shouldMoveThroughApplyWhenTheEventIsAllowed() {
        var filing = new ProductFeedbackFiling(UUID.randomUUID(), FilingState.DRAFT_QUEUED, false, Instant.EPOCH);
        ReflectionTestUtils.setField(filing, "state", FilingState.HELD);

        filing.apply(Event.KEPT_PRIVATE);

        assertThat(filing.getState()).isEqualTo(FilingState.QUEUED);
    }
}
