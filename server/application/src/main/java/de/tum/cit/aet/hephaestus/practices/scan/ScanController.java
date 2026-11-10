package de.tum.cit.aet.hephaestus.practices.scan;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.core.web.CursorPageDTO;
import de.tum.cit.aet.hephaestus.core.web.OffsetPageDTO;
import de.tum.cit.aet.hephaestus.core.web.PublicApiUrls;
import de.tum.cit.aet.hephaestus.practices.scan.dto.CancelScanRequestDTO;
import de.tum.cit.aet.hephaestus.practices.scan.dto.ScanDetailDTO;
import de.tum.cit.aet.hephaestus.practices.scan.dto.ScanReportDTO;
import de.tum.cit.aet.hephaestus.practices.scan.dto.ScanReportItemDTO;
import de.tum.cit.aet.hephaestus.practices.scan.dto.ScanReportProblemDTO;
import de.tum.cit.aet.hephaestus.practices.scan.dto.ScanSummaryDTO;
import de.tum.cit.aet.hephaestus.practices.scan.dto.StartScanRequestDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@WorkspaceScopedController
@ConditionalOnServerRole
@RequireAtLeastWorkspaceAdmin
@RequiredArgsConstructor
@Validated
@RequestMapping("/practices/scans")
public class ScanController {
    private final ScanQueryService query;
    private final PublicApiUrls publicApiUrls;
    private final ScanExportService exports;
    private final ScanRequestService requests;
    private final ScanReportItemsService items;

    @GetMapping
    @Operation(operationId = "listScans", summary = "List repository scans")
    public OffsetPageDTO<ScanSummaryDTO> listScans(
            WorkspaceContext context,
            @RequestParam(required = false) @Nullable String repositoryItemId,
            @RequestParam(required = false) @Nullable ReviewStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return OffsetPageDTO.from(query.list(
                context.id(),
                repositoryItemId == null ? null : Long.parseLong(repositoryItemId),
                status,
                PageRequest.of(page, size)));
    }

    @PostMapping
    @Operation(operationId = "startScan", summary = "Start a repository scan")
    @AuditExempt(reason = "This operation changes execution state recorded on the review, not configuration or access")
    @ApiResponse(responseCode = "202", description = "Accepted")
    public ResponseEntity<ScanSummaryDTO> startScan(
            WorkspaceContext context,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody StartScanRequestDTO request) {
        var scan =
                requests.start(context.id(), SecurityUtils.getCurrentAccountId().orElseThrow(), key, request);
        return ResponseEntity.accepted()
                .location(publicApiUrls.uri(
                        "/workspaces/{workspaceSlug}/practices/scans/{scanId}", context.slug(), scan.id()))
                .body(scan);
    }

    @GetMapping("/{scanId}")
    @Operation(operationId = "getScan", summary = "Read a repository scan")
    public ScanDetailDTO getScan(WorkspaceContext context, @PathVariable UUID scanId) {
        return query.detail(context.id(), scanId);
    }

    @PatchMapping("/{scanId}/status")
    @Operation(operationId = "updateScanStatus", summary = "Cancel a repository scan")
    @AuditExempt(reason = "This operation changes execution state recorded on the review, not configuration or access")
    @ApiResponse(responseCode = "200", description = "Completed")
    @ApiResponse(responseCode = "202", description = "Accepted")
    public ResponseEntity<ScanDetailDTO> cancelScan(
            WorkspaceContext context, @PathVariable UUID scanId, @Valid @RequestBody CancelScanRequestDTO request) {
        var detail = query.cancel(context.id(), scanId);
        return ResponseEntity.status(
                        detail.summary().status() == ReviewStatus.QUEUED
                                        || detail.summary().status() == ReviewStatus.RUNNING
                                ? 202
                                : 200)
                .body(detail);
    }

    @GetMapping("/{scanId}/report")
    @Operation(operationId = "getScanReport", summary = "Read a scan report")
    public ScanReportDTO getScanReport(WorkspaceContext context, @PathVariable UUID scanId) {
        return query.report(context.id(), scanId);
    }

    @GetMapping("/{scanId}/report/items")
    @Operation(operationId = "listScanReportItems", summary = "List the items in a scan report")
    public CursorPageDTO<ScanReportItemDTO> listScanReportItems(
            WorkspaceContext context,
            @PathVariable UUID scanId,
            @Valid @ParameterObject ScanReportItemFilterParams filter) {
        return items.items(context.id(), scanId, filter.getCursor(), filter.getSize(), filter.filter());
    }

    @GetMapping("/{scanId}/report/problems")
    @Operation(operationId = "listScanReportProblems", summary = "List the problems in a scan report")
    public CursorPageDTO<ScanReportProblemDTO> listScanReportProblems(
            WorkspaceContext context,
            @PathVariable UUID scanId,
            @Valid @ParameterObject ScanReportProblemFilterParams filter) {
        return items.problems(context.id(), scanId, filter.getCursor(), filter.getSize(), filter.filter());
    }

    @GetMapping(value = "/{scanId}/report/sarif", produces = "application/sarif+json")
    @Operation(operationId = "exportScanSarif", summary = "Export a scan report as SARIF")
    @ApiResponse(
            responseCode = "200",
            description = "SARIF report",
            content =
                    @Content(
                            mediaType = "application/sarif+json",
                            schema = @Schema(type = "string", format = "binary")))
    public ResponseEntity<byte[]> exportScanSarif(WorkspaceContext context, @PathVariable UUID scanId) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/sarif+json"))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"hephaestus-scan-" + scanId + ".sarif\"")
                .body(exports.sarif(context.id(), scanId));
    }
}
