package de.tum.cit.aet.hephaestus.practices.profile.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;

@Schema(description = "A page of the developer's own review runs, newest first")
public record ProfileReviewRunsPageDTO(
        @NonNull List<ProfileReviewRunDTO> content, int page, int size, boolean hasNext) {}
