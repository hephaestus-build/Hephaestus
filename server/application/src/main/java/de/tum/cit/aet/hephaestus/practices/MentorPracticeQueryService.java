package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaultsProvider;
import de.tum.cit.aet.hephaestus.practices.review.feedbackmode.FeedbackModeResolver;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.practices.spi.MentorPracticeQuery;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class MentorPracticeQueryService implements MentorPracticeQuery {
    private final PracticeRepository practices;
    private final PracticeVersionService versions;
    private final WorkspaceReviewDefaultsProvider defaults;

    @Override
    @Transactional(readOnly = true)
    public List<MentorPractice> current(long workspaceId) {
        var mode = defaults.rawDefaultFeedbackMode(workspaceId);
        return practices.findAllForCatalog(workspaceId).stream()
                .filter(practice ->
                        practice.getGroup() == null || practice.getGroup().isShownToDevelopers())
                .filter(practice -> FeedbackModeResolver.effectiveModeOf(practice, mode) != FeedbackMode.OFF)
                .map(practice ->
                        new MentorPractice(practice.getSlug(), versions.definitionOf(practice.getCurrentVersion())))
                .toList();
    }
}
