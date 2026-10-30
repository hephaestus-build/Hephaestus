package de.tum.cit.aet.hephaestus.practices.review.schedule;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.web.PageResponseDTO;
import de.tum.cit.aet.hephaestus.practices.review.schedule.dto.CreateReviewBatchRequestDTO;
import de.tum.cit.aet.hephaestus.practices.review.schedule.dto.ReviewBatchDTO;
import de.tum.cit.aet.hephaestus.practices.review.schedule.dto.UpdateReviewBatchStatusRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@ConditionalOnServerRole
@WorkspaceScopedController
@RequestMapping("/practices/review-batches")
@RequireAtLeastWorkspaceAdmin
@RequiredArgsConstructor
public class ReviewBatchController {
    private final ReviewBatchService batches;

    @GetMapping
    @Operation(operationId = "listReviewBatches", summary = "List review batches")
    public PageResponseDTO<ReviewBatchDTO> list(
            WorkspaceContext ws,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponseDTO.from(batches.list(ws, PageRequest.of(page, size)));
    }

    @PostMapping
    @Operation(operationId = "createReviewBatch", summary = "Create a review batch")
    @ApiResponse(responseCode = "201", description = "Created")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "REVIEW_BATCH")
    public ResponseEntity<ReviewBatchDTO> create(
            WorkspaceContext ws, @Valid @RequestBody CreateReviewBatchRequestDTO input) {
        return ResponseEntity.status(201).body(batches.create(ws, input));
    }

    @PatchMapping("/{batchId}/status")
    @Operation(operationId = "updateReviewBatchStatus", summary = "Pause, resume or cancel a review batch")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "REVIEW_BATCH")
    public ReviewBatchDTO status(
            WorkspaceContext ws,
            @PathVariable UUID batchId,
            @Valid @RequestBody UpdateReviewBatchStatusRequestDTO input,
            @RequestHeader(value = "If-Match", required = false) @Nullable String etag) {
        return batches.updateStatus(ws, batchId, input, etag == null ? null : EntityTagPrecondition.parse(etag));
    }
}
