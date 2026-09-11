package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.CreateSurveyDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.FeedbackItemDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.SubmissionDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.SurveyDTO;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/product-feedback")
@RecentSignInExempt(reason = "creates a survey; grants no access and stores no credential")
@PreAuthorize("hasAuthority('app_admin')")
@RequiredArgsConstructor
public class FeedbackAdminController {
    private final FeedbackService service;

    @GetMapping("/surveys")
    @Operation(operationId = "adminListProductSurveys", summary = "List authored product surveys")
    public PagedModel<SurveyDTO> surveys(
            @ParameterObject @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return new PagedModel<>(service.allSurveys(pageable));
    }

    @PostMapping("/surveys")
    @Operation(operationId = "adminCreateProductSurvey", summary = "Publish a product survey")
    @AuditExempt(reason = "the immutable survey row is the domain audit trail")
    public ResponseEntity<SurveyDTO> create(@Valid @RequestBody CreateSurveyDTO request) {
        SurveyDTO created = service.create(request, CurrentAccount.requireId());
        return ResponseEntity.created(URI.create("/admin/product-feedback/surveys/" + created.id()))
                .body(created);
    }

    @GetMapping("/responses")
    @Operation(operationId = "adminListProductSurveyResponses", summary = "List recent survey responses")
    public PagedModel<SubmissionDTO> responses(@ParameterObject @PageableDefault(size = 50) Pageable pageable) {
        return new PagedModel<>(service.responses(pageable));
    }

    @GetMapping
    @Operation(operationId = "adminListProductFeedback", summary = "List recent free-text product feedback")
    public PagedModel<FeedbackItemDTO> feedback(@ParameterObject @PageableDefault(size = 50) Pageable pageable) {
        return new PagedModel<>(service.feedback(pageable));
    }
}
