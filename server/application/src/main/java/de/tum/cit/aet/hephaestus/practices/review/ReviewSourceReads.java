package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.CapturedApplicability;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCaptureDecision;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Names the sources that a review's practice versions read, those that their applicability conditions read, and the
 * source fingerprints that the review queues for the practices it measures. It compares the sources that the locked
 * plan queues with those of the unlocked read, which ran before the workspace lock.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class ReviewSourceReads {
    private final JsonMapper json;
    private final List<CapturedApplicability> applicability;

    /**
     * What an item review reads before it takes the workspace lock: the currentness of the item's open results that an
     * event review compares, the sources that every practice the gate selects reads, and their fingerprints. A practice
     * that the item's results or its readiness leave out still counts, because the locked step can queue it.
     */
    record UnlockedRead(
            Map<UUID, Currentness> currentness, Set<SourceKind> kinds, Map<SourceKind, String> fingerprints) {
        static final UnlockedRead NONE = new UnlockedRead(Map.of(), Set.of(), Map.of());
    }

    /**
     * The fingerprints of the sources that the locked plan queues, from the unlocked read. A practice that came to read
     * another source after the unlocked read refuses the request until it is asked again.
     */
    static Map<SourceKind, String> fingerprints(Set<SourceKind> kinds, UnlockedRead unlocked) {
        if (!unlocked.kinds().containsAll(kinds))
            throw new ReviewRequestRejected(
                    ReviewRequestRejected.Reason.PRACTICES_CHANGED,
                    "The practices changed while the review was requested. Ask for the review again.");
        return unlocked.fingerprints();
    }

    Set<SourceKind> queuedKinds(ItemSnapshot item, List<ReviewPlanner.SelectedPractice> plans) {
        return readKinds(
                item,
                plans.stream()
                        .filter(plan -> plan.reason() == null)
                        .map(ReviewPlanner.SelectedPractice::version)
                        .toList());
    }

    /** The sources that these practice versions read, and those that their applicability conditions read. */
    Set<SourceKind> readKinds(ItemSnapshot item, List<PracticeVersion> versions) {
        var kinds = new HashSet<SourceKind>();
        for (var version : versions) {
            var definition = json.treeToValue(version.getDefinition(), PracticeDefinition.class);
            definition.reads().sources().forEach(read -> kinds.add(read.source()));
            var conditions = definition.appliesWhen();
            if (conditions == null) continue;
            var clauses = conditions.anyOf().stream()
                    .map(condition -> new CapturedApplicability.Condition(
                            condition.changedPath(),
                            condition.diffContains(),
                            condition.hasItems(),
                            Boolean.TRUE.equals(condition.merged())))
                    .toList();
            for (var adapter : applicability)
                adapter.evaluate(item.item().kind(), clauses, List.of(), Map.of())
                        .forEach(clause -> kinds.add(clause.readFrom()));
        }
        return Set.copyOf(kinds);
    }

    JsonNode queuedSources(Set<SourceKind> kinds, Map<SourceKind, String> fingerprints) {
        var facts = json.createObjectNode();
        var queued = facts.putObject(ReviewCaptureDecision.SOURCE_FINGERPRINTS);
        fingerprints.forEach((kind, value) -> {
            if (kinds.contains(kind)) queued.put(kind.value(), value);
        });
        return facts;
    }
}
