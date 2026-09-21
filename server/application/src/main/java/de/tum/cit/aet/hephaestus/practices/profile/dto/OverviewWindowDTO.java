package de.tum.cit.aet.hephaestus.practices.profile.dto;

import de.tum.cit.aet.hephaestus.practices.profile.OverviewWindow;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.NonNull;

@Schema(description = "The span the overview measured change over")
public record OverviewWindowDTO(
        @NonNull @Schema(description = "The window opens after this moment: the run before the latest one")
        Instant since,

        @NonNull @Schema(description = "The window closes at this moment, and the profile is read as of it")
        Instant until) {
    public static OverviewWindowDTO from(OverviewWindow window) {
        return new OverviewWindowDTO(window.since(), window.until());
    }
}
