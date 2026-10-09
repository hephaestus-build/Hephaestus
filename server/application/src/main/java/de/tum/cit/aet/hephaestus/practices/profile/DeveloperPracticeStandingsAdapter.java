package de.tum.cit.aet.hephaestus.practices.profile;

import de.tum.cit.aet.hephaestus.practices.curated.BundledPracticeCatalogLoader;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeStandingService;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeStandingService.StandingSnapshot.PracticeStanding;
import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO;
import de.tum.cit.aet.hephaestus.practices.spi.DeveloperPracticeStandings;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The Practice profile's standings of one developer, in the profile's own words. */
@Component
@RequiredArgsConstructor
class DeveloperPracticeStandingsAdapter implements DeveloperPracticeStandings {

    private final PracticeStandingService standings;
    private final BundledPracticeCatalogLoader bundledCatalog;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<Standing> standings(long workspaceId, long developerId) {
        return standings
                .getStandingSnapshots(workspaceId, developerId, List.of(clock.instant()))
                .getFirst()
                .practices()
                .values()
                .stream()
                .map(this::standing)
                .toList();
    }

    private Standing standing(PracticeStanding practice) {
        PracticeStandingDTO dto = practice.dto();
        return new Standing(
                dto.slug(),
                dto.name(),
                dto.groupName(),
                label(dto.standing()),
                PracticeStandingDTO.isVerdict(dto.standing()),
                practice.decidedWork(),
                dto.direction() == null ? null : dto.direction().name(),
                dto.toWorkOn().size(),
                dto.strengths().size(),
                practice.isHolding(standings.standingWindow()) ? holdsAs(practice) : null);
    }

    /** The Practice profile's words for a standing, and for the two reasons there is none. */
    private static String label(PracticeStandingDTO.Standing standing) {
        return switch (standing) {
            case DEVELOPING -> "Needs attention";
            case MIXED -> "Mixed feedback";
            case STRENGTH -> "Going well";
            case NO_OPPORTUNITY -> "No opportunity in the reviewed work";
            case NOT_OBSERVED -> "Not reviewed yet";
        };
    }

    private @Nullable String holdsAs(PracticeStanding practice) {
        if (practice.evidence().isEmpty()) {
            return null;
        }
        Practice source = practice.evidence().getFirst().getPractice();
        String sourceSlug = source.getSourceCuratedSlug();
        return sourceSlug == null ? null : bundledCatalog.holdsAs(sourceSlug).orElse(null);
    }
}
