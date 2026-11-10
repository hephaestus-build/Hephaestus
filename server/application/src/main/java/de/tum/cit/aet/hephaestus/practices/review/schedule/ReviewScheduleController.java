package de.tum.cit.aet.hephaestus.practices.review.schedule;

import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.review.schedule.dto.ReviewScheduleDTO;
import de.tum.cit.aet.hephaestus.practices.review.schedule.dto.ReviewScheduleInputDTO;
import de.tum.cit.aet.hephaestus.practices.review.schedule.dto.SetReviewScheduleEnabledRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

@ConditionalOnServerRole
@WorkspaceScopedController
@RequestMapping("/practices/review-schedules")
@RequireAtLeastWorkspaceAdmin
@RequiredArgsConstructor
public class ReviewScheduleController {
    private final ReviewScheduleService schedules;

    @GetMapping
    @Operation(operationId = "listReviewSchedules", summary = "List review schedules")
    public List<ReviewScheduleDTO> list(WorkspaceContext ws) {
        return schedules.list(ws);
    }

    @PostMapping
    @Operation(operationId = "createReviewSchedule", summary = "Create a review schedule")
    @ApiResponse(responseCode = "201", description = "Created")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "REVIEW_SCHEDULE")
    public ResponseEntity<ReviewScheduleDTO> create(
            WorkspaceContext ws, @Valid @RequestBody ReviewScheduleInputDTO input) {
        return ResponseEntity.status(201).body(schedules.create(ws, input));
    }

    @PutMapping("/{scheduleId}")
    @Operation(operationId = "updateReviewSchedule", summary = "Update a review schedule")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "REVIEW_SCHEDULE")
    public ReviewScheduleDTO replace(
            WorkspaceContext ws,
            @PathVariable UUID scheduleId,
            @Valid @RequestBody ReviewScheduleInputDTO input,
            @RequestHeader(value = "If-Match", required = false) @Nullable String etag) {
        return schedules.replace(ws, scheduleId, input, precondition(etag));
    }

    @PatchMapping("/{scheduleId}")
    @Operation(operationId = "setReviewScheduleEnabled", summary = "Enable or disable a review schedule")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "REVIEW_SCHEDULE")
    public ReviewScheduleDTO enabled(
            WorkspaceContext ws,
            @PathVariable UUID scheduleId,
            @Valid @RequestBody SetReviewScheduleEnabledRequestDTO input,
            @RequestHeader(value = "If-Match", required = false) @Nullable String etag) {
        return schedules.enabled(ws, scheduleId, input.enabled(), precondition(etag));
    }

    @RecentSignInExempt(reason = "a workspace admin can schedule the review again")
    @DeleteMapping("/{scheduleId}")
    @Operation(operationId = "deleteReviewSchedule", summary = "Delete an unused review schedule")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "REVIEW_SCHEDULE")
    public ResponseEntity<Void> delete(
            WorkspaceContext ws,
            @PathVariable UUID scheduleId,
            @RequestHeader(value = "If-Match", required = false) @Nullable String etag) {
        schedules.delete(ws, scheduleId, precondition(etag));
        return ResponseEntity.noContent().build();
    }

    private static @Nullable EntityTagPrecondition precondition(@Nullable String value) {
        return value == null ? null : EntityTagPrecondition.parse(value);
    }
}
