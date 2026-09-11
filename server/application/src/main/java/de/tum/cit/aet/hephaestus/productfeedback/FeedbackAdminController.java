package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.FeedbackFilter;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.FeedbackItemDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.FeedbackTriageDTO;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** The instance feedback inbox. */
@RestController
@RequestMapping("/admin/product-feedback")
@RecentSignInExempt(reason = "reads and triages product feedback; grants no access and stores no credential")
@PreAuthorize("hasAuthority('app_admin')")
@RequiredArgsConstructor
public class FeedbackAdminController {
    private final FeedbackService service;

    @GetMapping
    @Operation(operationId = "adminListProductFeedback", summary = "List product feedback, newest first")
    public PagedModel<FeedbackItemDTO> list(
            @RequestParam(defaultValue = "OPEN") FeedbackFilter status,
            @ParameterObject @PageableDefault(size = 50) Pageable pageable) {
        return new PagedModel<>(service.list(status, pageable));
    }

    @PatchMapping("/{feedbackId}")
    @Operation(operationId = "adminTriageProductFeedback", summary = "Mark product feedback as resolved or reopen it")
    @AuditExempt(reason = "inbox bookkeeping; grants no access and changes no configuration")
    public FeedbackItemDTO triage(@PathVariable UUID feedbackId, @Valid @RequestBody FeedbackTriageDTO request) {
        return service.triage(feedbackId, request.resolved(), CurrentAccount.requireId());
    }
}
