package de.tum.cit.aet.hephaestus.practices.feedback.decision;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackQueryRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
@ConditionalOnServerRole
@Transactional(readOnly = true)
public class FeedbackContentDigest {
    private final FeedbackQueryRepository queries;
    private final ObjectMapper mapper;

    public String current(long workspaceId, UUID feedbackId) {
        return queries.contentDigest(workspaceId, feedbackId)
                .orElseThrow(() -> new EntityNotFoundException("Feedback", feedbackId.toString()));
    }

    public String orderedPackageDigest(List<String> pieces) {
        return queries.orderedPackageDigest(mapper.writeValueAsString(pieces));
    }
}
