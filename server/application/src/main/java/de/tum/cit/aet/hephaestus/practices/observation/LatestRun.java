package de.tum.cit.aet.hephaestus.practices.observation;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A piece of reviewed work counts once, at its newest review — the rule the glossary states for how feedback
 * resolves, and the one the trend, the work resolution, the standing and the in-app lane all read their
 * evidence by. The run holding a piece of work's newest observation speaks for that work; what an earlier run
 * said about the same pull request is superseded, whichever way it went. Ties on the timestamp break on the
 * job id so a window whose timestamps collide still answers deterministically.
 */
public final class LatestRun {

    private LatestRun() {}

    /** The run that reviewed these rows' work last. The caller passes at least one row. */
    public static UUID of(Collection<Observation> observations) {
        return observations.stream()
                .max(Comparator.comparing(Observation::getObservedAt).thenComparing(Observation::getAgentJobId))
                .map(Observation::getAgentJobId)
                .orElseThrow();
    }

    /**
     * Narrows one practice's window to each piece of work's newest run, in the window's order: a row from an
     * earlier run of the same work is dropped, so a problem a later review no longer found is not in the answer.
     */
    public static List<Observation> perWork(Collection<Observation> observations) {
        return latest(observations, Work::of);
    }

    /**
     * Narrows a developer's whole window to the newest run of each claim: one practice on one piece of work,
     * read within one origin class. A run does not necessarily evaluate every practice, so correlating on the
     * work alone would let a later run supersede a verdict it never re-examined, and a partial capture or a
     * timeout would read like a fixed habit. A campaign's reading and a live reading of the same work are two
     * claims: origin-blind, a later campaign would erase already-delivered live feedback from the answer.
     */
    public static List<Observation> perClaim(Collection<Observation> observations) {
        return latest(observations, Claim::of);
    }

    private static <K> List<Observation> latest(Collection<Observation> observations, Function<Observation, K> key) {
        Map<K, UUID> latestByKey = observations.stream()
                .collect(Collectors.groupingBy(key, Collectors.collectingAndThen(Collectors.toList(), LatestRun::of)));
        return observations.stream()
                .filter(observation -> observation.getAgentJobId().equals(latestByKey.get(key.apply(observation))))
                .toList();
    }

    private record Work(ArtifactKind kind, Long id) {
        static Work of(Observation observation) {
            return new Work(observation.getArtifactKind(), observation.getArtifactId());
        }
    }

    private record Claim(String practiceSlug, Work work, boolean backfilled) {
        static Claim of(Observation observation) {
            return new Claim(
                    observation.getPractice().getSlug(),
                    Work.of(observation),
                    observation.getOrigin() == ObservationOrigin.BACKFILL);
        }
    }
}
