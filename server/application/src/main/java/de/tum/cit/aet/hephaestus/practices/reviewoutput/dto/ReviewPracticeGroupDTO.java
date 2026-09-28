package de.tum.cit.aet.hephaestus.practices.reviewoutput.dto;

import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.NamedInterface;

@NamedInterface("review-output")
public record ReviewPracticeGroupDTO(
        @NonNull String slug,
        @NonNull String name,
        @Schema(description = "Lucide icon name") @Nullable String icon,
        @Schema(description = "Palette color key") @Nullable String color) {
    public static ReviewPracticeGroupDTO from(PracticeGroup group) {
        return new ReviewPracticeGroupDTO(group.getSlug(), group.getName(), group.getIcon(), group.getColor());
    }

    public static @Nullable ReviewPracticeGroupDTO from(
            @Nullable String slug, @Nullable String name, @Nullable String icon, @Nullable String color) {
        return slug == null || name == null ? null : new ReviewPracticeGroupDTO(slug, name, icon, color);
    }
}
