package de.tum.cit.aet.hephaestus.core.webvitals;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** What a browser reports about how a page felt to the reader. It names no one. */
public final class WebVitalsDTOs {

    private WebVitalsDTOs() {}

    /** The Core Web Vitals and the server response time, as the `web-vitals` library names them. */
    public enum Vital {
        /** Largest contentful paint: when the page showed its main content. */
        LCP,
        /** Interaction to next paint: how long the slowest interaction took to show its result. */
        INP,
        /** Cumulative layout shift: how much the page moved under the reader. A score, not a time. */
        CLS,
        /** Time to first byte of the page document. */
        TTFB
    }

    /** The journey page a visit started on. Every other page is `OTHER`, so the set stays small. */
    public enum Page {
        PRACTICE_PROFILE,
        ASK_HEPHAESTUS,
        FEEDBACK,
        FEEDBACK_APPROVAL,
        ACTIVITY,
        OTHER
    }

    /** The landmark of the element that the measurement names: the largest paint, the slowest target, the largest shift. */
    public enum Region {
        SIDEBAR,
        HEADER,
        PAGE,
        OTHER
    }

    @Schema(description = "One measurement of a page visit")
    public record WebVitalDTO(
            @NotNull Vital vital,

            @NotNull Page page,

            @NotNull Region region,

            @Schema(
                    description =
                            "Milliseconds for LCP, INP and TTFB; the unitless layout-shift score for CLS, at most 100",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            // Boxed: Jackson reads a missing or null primitive as 0, a measurement nobody took.
            @NotNull
            @DecimalMin("0")
            @DecimalMax("600000")
            Double value) {}

    @Schema(description = "The measurements of one page visit, one of each vital, sent once when the reader leaves it")
    public record WebVitalsReportDTO(
            @NotNull @Size(min = 1, max = 4) List<@Valid @NotNull WebVitalDTO> vitals) {}
}
