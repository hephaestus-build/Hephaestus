package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.result.PracticeStandingService.StandingSnapshot.PracticeStandingSnapshot;
import de.tum.cit.aet.hephaestus.practices.result.dto.PracticeStandingDTO;
import de.tum.cit.aet.hephaestus.practices.spi.DeveloperPracticeStandings;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeStanding;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Processing-qualified standings of one developer, using the Practice profile's selection and words. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class DeveloperPracticeStandingsAdapter implements DeveloperPracticeStandings {

    private final PracticeStandingService standings;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<Standing> standings(long workspaceId, long accountId) {
        int window = standings.standingWindow();
        return standings
                .getProcessingStandingSnapshots(workspaceId, accountId, List.of(clock.instant()))
                .getFirst()
                .practices()
                .values()
                .stream()
                .map(practice -> standing(practice, window))
                .toList();
    }

    private static Standing standing(PracticeStandingSnapshot practice, int window) {
        PracticeStandingDTO dto = practice.dto();
        return new Standing(
                dto.slug(),
                dto.name(),
                dto.groupName(),
                label(dto.standing()),
                isVerdict(dto.standing()),
                decidedWork(practice),
                dto.direction() == null ? null : dto.direction().name(),
                dto.toWorkOn().size(),
                dto.strengths().size(),
                practice.isHolding(window));
    }

    /**
     * How many pieces of work reached a verdict in the population the standing was read off: the live runs when one
     * judged the practice, else the requested and backfilled work.
     */
    static int decidedWork(PracticeStandingSnapshot practice) {
        int live = practice.trend().cleanWork().decidedWork();
        if (live > 0) {
            return live;
        }
        return (int) practice.evidence().stream()
                .filter(view -> view.result().getOutcome() == ResultOutcome.MET
                        || view.result().getOutcome() == ResultOutcome.NOT_MET)
                .map(view -> view.result().getItemId())
                .distinct()
                .count();
    }

    private static boolean isVerdict(PracticeStanding standing) {
        return standing != PracticeStanding.NOT_OBSERVED && standing != PracticeStanding.NO_OPPORTUNITY;
    }

    /** The Practice profile's words for a standing, and for the two reasons there is none. */
    private static String label(PracticeStanding standing) {
        return switch (standing) {
            case DEVELOPING -> "Needs attention";
            case MIXED -> "Mixed feedback";
            case STRENGTH -> "Going well";
            case NO_OPPORTUNITY -> "Nothing to report yet";
            case NOT_OBSERVED -> "Not reviewed";
        };
    }
}
