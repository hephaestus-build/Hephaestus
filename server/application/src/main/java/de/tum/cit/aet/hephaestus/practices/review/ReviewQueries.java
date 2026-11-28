package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ReviewQueries {
    /** @param setupId the review setup whose reviews to list, or null for reviews of any setup or of none */
    Page<Review> search(long workspaceId, ReviewFilterParams filter, @Nullable UUID setupId, Pageable page);
}
