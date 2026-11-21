package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventDefinition;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.OccasionKind;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeItemOptions;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackModeQuery;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * A practice in feedback mode Off records observations at the milestones of its work, not on every push: its feedback
 * reaches no one, so a continuing occasion leaves it out, and the review of a push stays as short as the practices whose
 * feedback someone reads. A practice that reviews on no milestone or outcome of the item kind, such as one of a
 * conversation thread, has no other moment, so its continuing occasions keep it.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class OffPracticeMilestones {
    private final FeedbackModeQuery feedbackModes;
    private final PracticeItemOptions itemOptions;
    private final JsonMapper json;

    /** Whether every one of {@code occasions} continues: none of them is a milestone or an outcome. */
    boolean continuing(ItemKind kind, Collection<ItemEventName> occasions) {
        return occasions.stream().allMatch(name -> itemOptions.occasion(kind, name) == OccasionKind.CONTINUING);
    }

    /** The practices that a continuing occasion of {@code kind} leaves out until a milestone. */
    Set<Long> waitingForAMilestone(long workspaceId, ItemKind kind, Collection<Practice> practices) {
        var modes = feedbackModes.effectiveModes(
                workspaceId, practices.stream().map(Practice::getId).toList());
        var moments = itemOptions.require(kind).events().stream()
                .filter(event -> event.occasion() != OccasionKind.CONTINUING)
                .map(ItemEventDefinition::name)
                .collect(Collectors.toSet());
        return practices.stream()
                .filter(practice -> modes.getOrDefault(practice.getId(), FeedbackMode.OFF) == FeedbackMode.OFF)
                .filter(practice -> json
                        .convertValue(practice.getCurrentVersion().getDefinition(), PracticeDefinition.class)
                        .reviewOn()
                        .events()
                        .stream()
                        .anyMatch(moments::contains))
                .map(Practice::getId)
                .collect(Collectors.toUnmodifiableSet());
    }
}
