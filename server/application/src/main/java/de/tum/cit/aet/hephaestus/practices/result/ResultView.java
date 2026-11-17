package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import de.tum.cit.aet.hephaestus.practices.model.ResultFacts;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record ResultView(
        ResultFacts result,
        Practice practice,
        @Nullable PracticeVersion version,
        Review review,
        ItemRefDTO item,
        SourceFreshness freshness,
        @Nullable FeedbackMode sealedFeedbackMode) {

    /** Whether the work still reads as it did when the result was captured. Read on first use, not with the view. */
    public Freshness sourceFreshness() {
        return freshness.of(result);
    }

    /** Whether the work still reads as the capture read it, which a quote of the result needs. */
    public boolean hasCurrentSources() {
        return sourceFreshness().currentness() == Currentness.CURRENT;
    }

    public ResultView {
        long workspaceId = result.workspaceId();
        if (!Objects.equals(practice.getWorkspace().getId(), workspaceId)
                || review.getWorkspaceId() != workspaceId
                || review.getId().compareTo(result.reviewId()) != 0
                || !Long.toString(result.itemId()).equals(item.id())
                || !Long.valueOf(result.practiceId()).equals(practice.getId())
                || (version != null
                        && (version.getWorkspaceId() != workspaceId
                                || !Objects.equals(version.getId(), result.practiceVersionId())))) {
            throw new IllegalArgumentException("Result view crosses its stored identity or workspace");
        }
    }
}
