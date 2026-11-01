package de.tum.cit.aet.hephaestus.core.browserfaults;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.browserfaults.BrowserFaultsDTOs.BrowserFaultsReportDTO;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.security.Principal;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives the failures that readers met in the browser, signed in or not: a broken sign-in page is a
 * failure too. Nothing is stored. A request that the browser marks as sent from another site is
 * refused. Workspace addresses call the API from a sibling host, which the browser marks `same-site`.
 */
@ConditionalOnServerRole
@ConditionalOnBooleanProperty(BrowserFaultRecorder.ENABLED)
@RestController
@WorkspaceAgnostic("A browser fault names no workspace, so no workspace sees another's faults")
@RequestMapping("/browser-faults")
@Tag(name = "Browser faults", description = "Failures that readers met in the browser")
public class BrowserFaultsController {

    private final BrowserFaultRecorder recorder;

    public BrowserFaultsController(BrowserFaultRecorder recorder) {
        this.recorder = recorder;
    }

    @PostMapping
    @Operation(summary = "Record the faults that one page met", operationId = "reportBrowserFaults")
    @SecurityRequirements
    @ApiResponse(responseCode = "204", description = "Faults recorded")
    @ApiResponse(responseCode = "400", description = "A fault names an unknown value or is out of range")
    @ApiResponse(
            responseCode = "403",
            description = "The browser sent the report from another site, or the CSRF token is missing")
    @ApiResponse(responseCode = "413", description = "The report is larger than 16 KiB")
    @ApiResponse(responseCode = "429", description = "The reader or address sent too many reports")
    public ResponseEntity<Void> report(
            @Valid @RequestBody BrowserFaultsReportDTO report,
            // The browser sets this header itself; no client chooses it, so the contract does not name it.
            @Parameter(hidden = true) @RequestHeader(name = "Sec-Fetch-Site", required = false) @Nullable
                    String fetchSite,
            @Parameter(hidden = true) @Nullable Principal reader) {
        if ("cross-site".equals(fetchSite)) {
            return ResponseEntity.status(403).build();
        }
        report.faults().forEach(fault -> recorder.record(fault, reader != null));
        return ResponseEntity.noContent().build();
    }
}
