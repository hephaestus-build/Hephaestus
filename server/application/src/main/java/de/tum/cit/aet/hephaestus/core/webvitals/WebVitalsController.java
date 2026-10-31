package de.tum.cit.aet.hephaestus.core.webvitals;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.webvitals.WebVitalsDTOs.Navigation;
import de.tum.cit.aet.hephaestus.core.webvitals.WebVitalsDTOs.Vital;
import de.tum.cit.aet.hephaestus.core.webvitals.WebVitalsDTOs.WebVitalsReportDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Receives how page visits felt to signed-in readers, for the operator's metrics. Nothing is stored. */
@ConditionalOnServerRole
@ConditionalOnBooleanProperty(WebVitalsMetrics.ENABLED)
@RestController
@WorkspaceAgnostic("A measurement names no workspace, so no workspace sees another's visits")
@RequestMapping("/web-vitals")
@Tag(name = "Web vitals", description = "Page load and interaction timings from the browser")
@PreAuthorize("isAuthenticated()")
public class WebVitalsController {

    private static final double MAX_LAYOUT_SHIFT = 100;

    private final WebVitalsMetrics metrics;

    public WebVitalsController(WebVitalsMetrics metrics) {
        this.metrics = metrics;
    }

    @PostMapping
    @Operation(summary = "Record the measurements of one page visit", operationId = "reportWebVitals")
    @ApiResponse(responseCode = "204", description = "Measurements recorded")
    @ApiResponse(
            responseCode = "400",
            description =
                    "A measurement names an unknown vital, navigation, page or region, is out of range, or is the time to first byte of a route view")
    @ApiResponse(responseCode = "401", description = "Not signed in")
    public ResponseEntity<Void> report(@Valid @RequestBody WebVitalsReportDTO report) {
        if (!isPlausible(report)) {
            return ResponseEntity.badRequest().build();
        }
        report.vitals().forEach(metrics::record);
        return ResponseEntity.noContent().build();
    }

    /** A route view loads no document, so it has no time to first byte, and a layout shift score above 100 is no real page. */
    private static boolean isPlausible(WebVitalsReportDTO report) {
        return report.vitals().stream()
                .noneMatch(vital -> (vital.vital() == Vital.TTFB && vital.navigation() == Navigation.ROUTE)
                        || (vital.vital() == Vital.CLS && vital.value() > MAX_LAYOUT_SHIFT));
    }
}
