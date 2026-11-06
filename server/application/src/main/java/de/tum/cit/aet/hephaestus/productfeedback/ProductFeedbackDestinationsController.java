package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.RequireInstanceAdmin;
import de.tum.cit.aet.hephaestus.core.RequiresRecentSignIn;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.productfeedback.FilingDTOs.ProductFeedbackDestinationsDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FilingDTOs.ProductFeedbackNeedsActionListDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FilingDTOs.UpdateProductFeedbackDestinationsDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Where the instance files product feedback, its health, and the reports that failed to file. */
@RestController
@ConditionalOnServerRole
@RequestMapping("/admin/product-feedback")
@WorkspaceAgnostic("Product feedback destinations are instance-wide")
@RequireInstanceAdmin
@RequiredArgsConstructor
public class ProductFeedbackDestinationsController {
    private final DestinationService destinations;
    private final FilingService filing;
    private final CleanupService cleanups;

    @GetMapping("/destinations")
    @Operation(
            operationId = "getProductFeedbackDestinations",
            summary = "Get the product feedback destinations and their health")
    public ProductFeedbackDestinationsDTO get() {
        return destinations.get();
    }

    @PutMapping("/destinations")
    @RequiresRecentSignIn
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "PRODUCT_FEEDBACK_DESTINATIONS_CHANGED")
    @Operation(operationId = "updateProductFeedbackDestinations", summary = "Set where product feedback is filed")
    public ProductFeedbackDestinationsDTO update(@Valid @RequestBody UpdateProductFeedbackDestinationsDTO request) {
        return destinations.update(request, CurrentAccount.requireId());
    }

    @GetMapping("/needs-action")
    @Operation(
            operationId = "listProductFeedbackNeedsAction",
            summary = "List reports that failed to file and erased reports whose issues need a manual step")
    public ProductFeedbackNeedsActionListDTO needsAction() {
        return new ProductFeedbackNeedsActionListDTO(filing.needsAction(), cleanups.needsAction());
    }

    @PostMapping("/{feedbackId}/retry")
    @RecentSignInExempt(reason = "files a stopped report again; changes no destination and grants no access")
    @AuditExempt(reason = "sends a stopped report through filing again; changes no configuration")
    @Operation(operationId = "retryProductFeedbackFiling", summary = "File a stopped report again")
    public ResponseEntity<Void> retry(@PathVariable UUID feedbackId) {
        if (!filing.retry(feedbackId))
            throw new EntityNotFoundException("Stopped product feedback", feedbackId.toString());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/file-saved")
    @RequiresRecentSignIn
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "PRODUCT_FEEDBACK_FILED_BY_ADMIN")
    @Operation(
            operationId = "fileAllSavedProductFeedback",
            summary = "Send every report that is saved for admins to GitHub as private issues")
    public FilingDTOs.ProductFeedbackMoveDTO fileAll() {
        return new FilingDTOs.ProductFeedbackMoveDTO(filing.fileAllSaved(CurrentAccount.requireId()));
    }

    @PostMapping("/{feedbackId}/file")
    @RequiresRecentSignIn
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "PRODUCT_FEEDBACK_FILED_BY_ADMIN")
    @Operation(
            operationId = "fileSavedProductFeedback",
            summary = "Send a report that is saved for admins to GitHub as a private issue")
    @ApiResponse(responseCode = "204", description = "The report goes through drafting and routing")
    public ResponseEntity<Void> file(@PathVariable UUID feedbackId) {
        if (!filing.fileSaved(feedbackId, CurrentAccount.requireId()))
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "This report cannot go to GitHub now. Check the destination and Pause.");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/cleanups/{cleanupId}/confirm")
    @RecentSignInExempt(reason = "confirms a manual erasure step that is done on GitHub; grants no access")
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "PRODUCT_FEEDBACK_CLEANUP_CONFIRMED")
    @Operation(
            operationId = "confirmProductFeedbackCleanup",
            summary = "Confirm that a maintainer finished an erased report's manual step on GitHub")
    @ApiResponse(responseCode = "204", description = "The step is confirmed and its issue locator is deleted")
    public ResponseEntity<Void> confirmCleanup(@PathVariable UUID cleanupId) {
        if (!cleanups.confirm(cleanupId, CurrentAccount.requireId()))
            throw new EntityNotFoundException("Product feedback cleanup step", cleanupId.toString());
        return ResponseEntity.noContent().build();
    }
}
