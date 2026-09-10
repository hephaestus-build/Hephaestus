package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireWorkspaceOwner;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@WorkspaceScopedController
@ConditionalOnServerRole
@RequiredArgsConstructor
@Validated
public class WorkspaceAccessController {
    private final WorkspaceAccessPolicyService policies;
    private final WorkspaceAccessEntryService entries;
    private final WorkspaceAccessRequestService requests;
    private final WorkspaceAccessNotifications notifications;
    private final WorkspaceAccessAdmissionService admission;

    @GetMapping("/access-entry")
    @PreAuthorize("permitAll()")
    @Operation(operationId = "getWorkspaceAccessEntry", summary = "Discover this workspace's primary sign-in option")
    public WorkspaceAccessEntryService.WorkspaceAccessEntryDTO entry(WorkspaceContext context) {
        return entries.entry(context.id());
    }

    @PostMapping("/access-requests/me/admission")
    @PreAuthorize("isAuthenticated()")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "WORKSPACE_ROLE")
    @Operation(
            operationId = "checkWorkspaceOrganizationMembership",
            summary = "Admit an existing organization member using fresh provider-native proof")
    public WorkspaceAccessAdmissionService.WorkspaceAccessAdmissionDTO admission(WorkspaceContext context) {
        return admission.check(context.id());
    }

    @GetMapping("/access-requests/me/form")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            operationId = "getWorkspaceAccessForm",
            summary = "Read the current access form after proving the primary identity")
    public WorkspaceAccessEntryService.WorkspaceAccessFormDTO form(WorkspaceContext context) {
        return entries.form(context.id());
    }

    @GetMapping("/access-requests/me")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            operationId = "getMyWorkspaceAccessRequests",
            summary = "Read your own access requests, including after expiry")
    public List<WorkspaceAccessRequestService.WorkspaceAccessRequestDTO> mine(WorkspaceContext context) {
        return requests.mine(context.id());
    }

    @PostMapping("/access-requests/me")
    @PreAuthorize("isAuthenticated()")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "WORKSPACE_ACCESS_REQUEST")
    @Operation(
            operationId = "submitWorkspaceAccessRequest",
            summary = "Submit an acknowledged access request or renewal")
    public WorkspaceAccessRequestService.WorkspaceAccessRequestDTO submit(
            WorkspaceContext context,
            @Valid @RequestBody WorkspaceAccessRequestService.SubmitWorkspaceAccessRequestDTO input) {
        return requests.submit(context.id(), input);
    }

    @DeleteMapping("/access-requests/me/{requestId}")
    @PreAuthorize("isAuthenticated()")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "WORKSPACE_ACCESS_REQUEST")
    @Operation(
            operationId = "withdrawWorkspaceAccessRequest",
            summary = "Withdraw your undecided request without changing existing access")
    public WorkspaceAccessRequestService.WorkspaceAccessRequestDTO cancel(
            WorkspaceContext context, @PathVariable Long requestId) {
        return requests.cancel(context.id(), requestId);
    }

    @GetMapping("/access-policy")
    @RequireAtLeastWorkspaceAdmin
    @Operation(operationId = "getWorkspaceAccessPolicy", summary = "Inspect access-request configuration")
    public WorkspaceAccessPolicyService.WorkspaceAccessPolicyDTO policy(WorkspaceContext context) {
        return policies.get(context.id());
    }

    @PutMapping("/access-policy")
    @RequireWorkspaceOwner
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "WORKSPACE_ACCESS_POLICY")
    @Operation(
            operationId = "configureWorkspaceAccessPolicy",
            summary = "Configure, pause or resume workspace access requests")
    public WorkspaceAccessPolicyService.WorkspaceAccessPolicyDTO configure(
            WorkspaceContext context,
            @Valid @RequestBody WorkspaceAccessPolicyService.ConfigureWorkspaceAccessPolicyDTO input) {
        return policies.configure(context.id(), input);
    }

    @GetMapping("/access-requests/options")
    @RequireAtLeastWorkspaceAdmin
    @Operation(
            operationId = "getWorkspaceAccessReviewOptions",
            summary = "List currently eligible maintainers and teams for access review")
    public WorkspaceAccessEntryService.WorkspaceAccessReviewOptionsDTO reviewOptions(WorkspaceContext context) {
        return entries.reviewOptions(context.id());
    }

    @GetMapping("/access-requests")
    @RequireAtLeastWorkspaceAdmin
    @Operation(operationId = "getWorkspaceAccessRequests", summary = "Review workspace access requests")
    public List<WorkspaceAccessRequestService.WorkspaceAccessRequestDTO> queue(
            WorkspaceContext context,
            @RequestParam(defaultValue = "SUBMITTED") WorkspaceAccessRequest.Status status,
            @RequestParam(defaultValue = "0") @Min(0) int page) {
        return requests.reviewQueue(context.id(), status, page);
    }

    @PatchMapping("/access-requests/{requestId}")
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "WORKSPACE_ACCESS_REQUEST")
    @Operation(
            operationId = "reviewWorkspaceAccessRequest",
            summary = "Approve, request changes or reject someone else's request")
    public WorkspaceAccessRequestService.WorkspaceAccessRequestDTO review(
            WorkspaceContext context,
            @PathVariable Long requestId,
            @Valid @RequestBody WorkspaceAccessRequestService.ReviewWorkspaceAccessRequestDTO input) {
        return requests.decide(context.id(), requestId, input);
    }

    @GetMapping("/access-requests/{requestId}/notifications")
    @RequireAtLeastWorkspaceAdmin
    @Operation(
            operationId = "getWorkspaceAccessNotifications",
            summary = "Inspect access email delivery without exposing addresses")
    public List<WorkspaceAccessNotifications.WorkspaceAccessNotificationDTO> notifications(
            WorkspaceContext context, @PathVariable Long requestId) {
        return notifications.list(context.id(), requestId);
    }

    @PostMapping("/access-notifications/{notificationId}/retries")
    @RequireAtLeastWorkspaceAdmin
    @AuditExempt(
            reason =
                    "Retries change delivery scheduling only; attempts and outcomes are recorded in the workspace notification outbox")
    @Operation(operationId = "retryWorkspaceAccessNotification", summary = "Retry an undelivered access notification")
    public WorkspaceAccessNotifications.WorkspaceAccessNotificationDTO retry(
            WorkspaceContext context, @PathVariable Long notificationId) {
        return notifications.retry(context.id(), notificationId);
    }
}
