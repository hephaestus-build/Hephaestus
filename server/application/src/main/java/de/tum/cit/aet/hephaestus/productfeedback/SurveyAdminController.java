package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.CreateSurveyDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.SurveyDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.SurveyEditDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.SurveyResponseDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.SurveySummaryDTO;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnServerRole
@RequestMapping("/admin/product-feedback/surveys")
@RecentSignInExempt(reason = "manages product surveys; grants no access and stores no credential")
@PreAuthorize("hasAuthority('app_admin')")
@RequiredArgsConstructor
public class SurveyAdminController {
    private final SurveyService service;
    private final SurveyEmailInvitationService emailInvitations;

    @GetMapping("/{surveyId}/email-invitations")
    @Operation(
            operationId = "adminPreviewSurveyEmailInvitations",
            summary = "Preview eligible survey email recipients and relay acceptance counts")
    public SurveyEmailInvitationSummaryDTO previewEmailInvitations(@PathVariable UUID surveyId) {
        return emailInvitations.preview(surveyId);
    }

    @PostMapping("/{surveyId}/email-invitations")
    @Operation(
            operationId = "adminSendSurveyEmailInvitations",
            summary = "Queue up to 1000 new survey email invitations")
    @AuditExempt(reason = "Each invitation records its requester and time; this changes no survey participation")
    public SurveyEmailInvitationSummaryDTO sendEmailInvitations(
            @PathVariable UUID surveyId,
            @RequestBody(required = false) @Valid @Nullable SurveyEmailInvitationRequestDTO request) {
        return emailInvitations.invite(surveyId, CurrentAccount.requireId(), request != null && request.sendReminder());
    }

    @GetMapping
    @Operation(operationId = "adminListProductSurveys", summary = "List product surveys with participation counts")
    public PagedModel<SurveyDTO> list(
            @ParameterObject @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return new PagedModel<>(service.all(pageable));
    }

    @PostMapping
    @Operation(operationId = "adminCreateProductSurvey", summary = "Publish a product survey")
    @AuditExempt(reason = "the survey row keeps its author and creation time; it grants no access")
    public ResponseEntity<SurveyDTO> create(@Valid @RequestBody CreateSurveyDTO request) {
        SurveyDTO created = service.create(request, CurrentAccount.requireId());
        return ResponseEntity.created(URI.create("/admin/product-feedback/surveys/" + created.id()))
                .body(created);
    }

    @GetMapping("/{surveyId}")
    @Operation(operationId = "adminGetProductSurvey", summary = "Read one product survey")
    public SurveyDTO get(@PathVariable UUID surveyId) {
        return service.get(surveyId);
    }

    @PutMapping("/{surveyId}")
    @Operation(
            operationId = "adminUpdateProductSurvey",
            summary = "Edit a survey's title, purpose, schedule or pause state")
    @AuditExempt(reason = "changes who is invited, never a stored answer; grants no access")
    public SurveyDTO update(@PathVariable UUID surveyId, @Valid @RequestBody SurveyEditDTO request) {
        return service.edit(surveyId, request);
    }

    @DeleteMapping("/{surveyId}")
    @Operation(operationId = "adminDeleteProductSurvey", summary = "Delete a survey and every response to it")
    @AuditExempt(
            reason =
                    "irreversibly removes members' answers; the actor is not recorded until the config-audit vocabulary has a survey type")
    public ResponseEntity<Void> delete(@PathVariable UUID surveyId) {
        service.delete(surveyId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{surveyId}/summary")
    @Operation(operationId = "adminGetProductSurveySummary", summary = "Aggregate the responses per question")
    public SurveySummaryDTO summary(@PathVariable UUID surveyId) {
        return service.summary(surveyId);
    }

    @GetMapping("/{surveyId}/responses")
    @Operation(operationId = "adminListProductSurveyResponses", summary = "List responses and declines, newest first")
    public PagedModel<SurveyResponseDTO> responses(
            @PathVariable UUID surveyId, @ParameterObject @PageableDefault(size = 50) Pageable pageable) {
        return new PagedModel<>(service.responses(surveyId, pageable));
    }

    @GetMapping(value = "/{surveyId}/responses/export", produces = "text/csv")
    @Operation(operationId = "adminExportProductSurveyResponses", summary = "Export responses and declines as CSV")
    public ResponseEntity<String> export(@PathVariable UUID surveyId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"survey-" + surveyId + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(service.exportCsv(surveyId));
    }
}
