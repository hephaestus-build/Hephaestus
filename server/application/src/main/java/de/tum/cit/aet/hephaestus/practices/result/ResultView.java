package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record ResultView(
        Result result,
        Practice practice,
        @Nullable PracticeVersion version,
        Review review,
        ItemRefDTO item,
        Currentness sourceCurrentness) {

    public ResultView {
        long workspaceId = result.getWorkspaceId();
        if (!Objects.equals(practice.getWorkspace().getId(), workspaceId)
                || review.getWorkspaceId() != workspaceId
                || review.getId().compareTo(result.getReviewId()) != 0
                || !Long.toString(result.getItemId()).equals(item.id())
                || !Long.valueOf(result.getPracticeId()).equals(practice.getId())
                || (version != null
                        && (version.getWorkspaceId() != workspaceId
                                || !Objects.equals(version.getId(), result.getPracticeVersionId())))) {
            throw new IllegalArgumentException("Result view crosses its stored identity or workspace");
        }
    }
}
