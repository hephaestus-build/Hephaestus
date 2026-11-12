package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.MemberAiRoutingAdapter;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.spi.BoundModelSlot;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewModelBindings;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Publishes the method's one review slot; credentials and prices stay in runtime admission. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ReviewModelBindingsAdapter implements ReviewModelBindings {
    private final MemberAiRoutingAdapter routing;
    private final ReviewMethodVerifier methods;

    @Override
    @Transactional(readOnly = true)
    public List<BoundModelSlot> models(long workspaceId, List<@Nullable Long> developerIds) {
        if (developerIds.isEmpty()) return List.of(new BoundModelSlot("review", ModelKind.CHAT, null));
        var review = routing.bindingForPeople(workspaceId, AgentPurpose.PRACTICE_REVIEW, developerIds);
        return List.of(new BoundModelSlot(
                "review", ModelKind.CHAT, review.map(row -> methods.freeze(row)).orElse(null)));
    }
}
