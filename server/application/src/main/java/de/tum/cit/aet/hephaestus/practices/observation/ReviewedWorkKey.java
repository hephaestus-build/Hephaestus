package de.tum.cit.aet.hephaestus.practices.observation;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.model.Observation;

/**
 * Identity of one piece of reviewed work: the same pull request reviewed twice is one piece of work. The key
 * every rule that counts work once groups by, so they all mean the same thing by "the same piece of work".
 */
public record ReviewedWorkKey(ArtifactKind kind, long id) {

    public static ReviewedWorkKey of(Observation observation) {
        return new ReviewedWorkKey(observation.getArtifactKind(), observation.getArtifactId());
    }
}
