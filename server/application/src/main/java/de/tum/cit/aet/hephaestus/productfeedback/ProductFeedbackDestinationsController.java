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
import de.tum.cit.aet.hephaestus.productfeedback.FilingDTOs.ProductFeedbackNeedsActionDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FilingDTOs.ProductFeedbackNeedsActionListDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FilingDTOs.ReportRoute;
import de.tum.cit.aet.hephaestus.productfeedback.FilingDTOs.UpdateProductFeedbackDestinationsDTO;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    private final ProductFeedbackRepository feedback;

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
    @Operation(operationId = "listProductFeedbackNeedsAction", summary = "List reports that failed to file")
    public ProductFeedbackNeedsActionListDTO needsAction() {
        var items = filing.stopped().stream()
                .flatMap(stopped -> feedback.findById(stopped.getFeedbackId()).stream()
                        .map(report -> new ProductFeedbackNeedsActionDTO(
                                report.getId(),
                                report.getKind(),
                                Objects.requireNonNull(report.getCreatedAt()),
                                stopped.getUpdatedAt(),
                                stopped.getReason() == null
                                        ? "unknown"
                                        : stopped.getReason().words(),
                                stopped.getRoute() == null
                                        ? ReportRoute.PRIVATE
                                        : ReportRoute.valueOf(stopped.getRoute().name()),
                                stopped.getRepository())))
                .toList();
        return new ProductFeedbackNeedsActionListDTO(items);
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
}
