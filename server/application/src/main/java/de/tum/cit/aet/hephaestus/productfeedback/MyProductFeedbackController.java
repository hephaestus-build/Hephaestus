package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.productfeedback.FilingDTOs.AllowPublicDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FilingDTOs.EditProductFeedbackDraftDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FilingDTOs.MyProductFeedbackDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FilingDTOs.ProductFeedbackSharingDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FilingDTOs.UpdateProductFeedbackSharingDTO;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A person's own product feedback reports and their choice about public issues. */
@RestController
@ConditionalOnServerRole
@RequestMapping("/product-feedback")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@WorkspaceAgnostic("A person's own product feedback belongs to their account, not to a workspace")
public class MyProductFeedbackController {
    private final MyReportsService reports;

    @GetMapping("/sharing")
    @Operation(
            operationId = "getProductFeedbackSharing",
            summary = "Get the current account's choice about public issues")
    public ProductFeedbackSharingDTO sharing() {
        return reports.sharing(CurrentAccount.requireId());
    }

    @PutMapping("/sharing")
    @AuditExempt(reason = "a person's own choice, recorded as an append-only consent event")
    @Operation(
            operationId = "updateProductFeedbackSharing",
            summary = "Choose whether reports can become public issues")
    public ProductFeedbackSharingDTO updateSharing(@Valid @RequestBody UpdateProductFeedbackSharingDTO request) {
        return reports.updateSharing(CurrentAccount.requireId(), request);
    }

    @GetMapping("/mine")
    @Operation(
            operationId = "listMyProductFeedback",
            summary = "List the current account's product feedback, newest first")
    public List<MyProductFeedbackDTO> mine() {
        return reports.mine(CurrentAccount.requireId());
    }

    @GetMapping("/{feedbackId}")
    @Operation(operationId = "getMyProductFeedback", summary = "Get one of the current account's reports")
    public MyProductFeedbackDTO get(@PathVariable UUID feedbackId) {
        return reports.get(CurrentAccount.requireId(), feedbackId);
    }

    @PutMapping("/{feedbackId}/draft")
    @AuditExempt(reason = "a person edits their own held report")
    @Operation(operationId = "editMyProductFeedbackDraft", summary = "Edit a held report before it is filed")
    public MyProductFeedbackDTO edit(
            @PathVariable UUID feedbackId, @Valid @RequestBody EditProductFeedbackDraftDTO request) {
        return reports.edit(CurrentAccount.requireId(), feedbackId, request);
    }

    @PostMapping("/{feedbackId}/keep-private")
    @AuditExempt(reason = "a person keeps their own report private")
    @Operation(operationId = "keepMyProductFeedbackPrivate", summary = "Keep a report private before it is filed")
    public MyProductFeedbackDTO keepPrivate(@PathVariable UUID feedbackId) {
        return reports.keepPrivate(CurrentAccount.requireId(), feedbackId);
    }

    @PostMapping("/{feedbackId}/make-private")
    @AuditExempt(reason = "a person withdraws their own public issue")
    @Operation(
            operationId = "makeMyProductFeedbackPrivate",
            summary = "Move the report's public issue to the private repository")
    public MyProductFeedbackDTO makePrivate(@PathVariable UUID feedbackId) {
        return reports.makePrivate(CurrentAccount.requireId(), feedbackId);
    }

    @PutMapping("/{feedbackId}/allow-public")
    @AuditExempt(reason = "a person lets maintainers publish their own private report")
    @Operation(
            operationId = "allowMyProductFeedbackPublic",
            summary = "Let maintainers publish a private report, or take that back")
    public MyProductFeedbackDTO allowPublic(@PathVariable UUID feedbackId, @Valid @RequestBody AllowPublicDTO request) {
        return reports.allowPublic(CurrentAccount.requireId(), feedbackId, request.allowPublic());
    }
}
