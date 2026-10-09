package de.tum.cit.aet.hephaestus.practices.observation;

import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkLabels;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkRefDTO;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where an observation can be read about: its practice, and the reviewed work its review was on. A surface that
 * shows feedback about an observation links there; this names the place, it does not decide who may see it.
 */
@Service
@RequiredArgsConstructor
public class ObservationReferences {

    private final ObservationRepository observationRepository;
    private final ReviewRunLookup reviewRunLookup;

    /** One observation's practice and the work it was seen on, as every surface names it. */
    public record Reference(String practiceSlug, String practiceName, ReviewedWorkRefDTO work) {}

    /** The references of those {@code observationIds} that exist in the workspace. */
    @Transactional(readOnly = true)
    public Map<UUID, Reference> find(long workspaceId, Collection<UUID> observationIds) {
        if (observationIds.isEmpty()) {
            return Map.of();
        }
        List<Observation> observations = observationRepository.findAllById(observationIds).stream()
                .filter(observation -> observation.getWorkspaceId() == workspaceId)
                .toList();
        Map<UUID, ReviewRunLookup.Target> targets = reviewRunLookup.findTargets(
                workspaceId,
                observations.stream().map(Observation::getAgentJobId).collect(Collectors.toSet()));
        Map<UUID, Reference> references = new LinkedHashMap<>();
        for (Observation observation : observations) {
            references.put(
                    observation.getId(),
                    new Reference(
                            observation.getPractice().getSlug(),
                            observation.getPractice().getName(),
                            ReviewedWorkLabels.ref(
                                    observation.getArtifactKind(),
                                    observation.getArtifactId(),
                                    targets.get(observation.getAgentJobId()))));
        }
        return references;
    }
}
