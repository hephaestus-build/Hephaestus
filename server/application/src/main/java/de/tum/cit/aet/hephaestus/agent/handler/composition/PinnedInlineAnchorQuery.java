package de.tum.cit.aet.hephaestus.agent.handler.composition;

import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionBasisRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
@ConditionalOnServerRole
public class PinnedInlineAnchorQuery {
    private final ReviewCompositionBasisRepository bases;
    private final ObjectMapper mapper;

    public @Nullable PinnedInlineAnchors placement(ReviewJob job) {
        var retained = bases.findByWorkspaceIdAndJobId(job.getWorkspaceId(), job.getId())
                .orElse(null);
        if (retained == null) return null;
        var change = retained.getPayload().path("files").path("context/change.json");
        if (!change.isString()) return null;
        try {
            return new PinnedInlineAnchors(mapper.readTree(Base64.getDecoder().decode(change.asString())));
        } catch (RuntimeException invalid) {
            return null;
        }
    }
}
