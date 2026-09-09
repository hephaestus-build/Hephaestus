package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditSnapshot;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.audit.WorkspaceAuditSnapshots.AccountMembershipSnapshot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
class WorkspaceAccessRequestService {
    private final WorkspaceRepository workspaces;
    private final WorkspaceAccessPolicyRepository policies;
    private final WorkspaceAccessRequestRepository requests;
    private final WorkspaceAccountMembershipRepository memberships;
    private final AccountIdentityQuery identities;
    private final WorkspaceAccessCatalog catalog;
    private final WorkspaceAccessNotifications notifications;
    private final ConfigAuditPort audit;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<WorkspaceAccessRequestDTO> mine(Long workspaceId) {
        return requests
                .findByWorkspace_IdAndAccountIdOrderBySubmittedAtDescIdDesc(
                        workspaceId, accountId(), PageRequest.of(0, 50))
                .stream()
                .map(this::view)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WorkspaceAccessRequestDTO> reviewQueue(
            Long workspaceId, WorkspaceAccessRequest.Status status, int page) {
        requireReviewer(workspaceId);
        return requests
                .findByWorkspace_IdAndStatusOrderBySubmittedAtAscIdAsc(workspaceId, status, PageRequest.of(page, 50))
                .stream()
                .map(this::view)
                .toList();
    }

    @Transactional
    public WorkspaceAccessRequestDTO submit(Long workspaceId, SubmitWorkspaceAccessRequestDTO submission) {
        var workspace = lock(workspaceId);
        Long accountId = accountId();
        lockAccount(accountId);
        var policy = enabledPolicy(workspaceId);
        if (policy.getVersion() != submission.policyVersion()) {
            throw conflict("The access policy changed; review and acknowledge the current version");
        }
        var settings = policy.getSettings();
        var noticeKeys = settings.notices().stream()
                .map(WorkspaceAccessPolicySettings.PolicyNoticeDTO::key)
                .toList();
        if (!submission.introductionAcknowledged()
                || submission.acknowledgedNoticeKeys().size() != noticeKeys.size()
                || !new HashSet<>(submission.acknowledgedNoticeKeys()).equals(new HashSet<>(noticeKeys))) {
            throw conflict("Acknowledge the introduction and each current workspace policy notice");
        }
        if (requests.existsByWorkspace_IdAndAccountIdAndStatusIn(
                workspaceId, accountId, List.of(WorkspaceAccessRequest.Status.SUBMITTED))) {
            throw conflict("An access request is already awaiting review");
        }
        requireRequestManagedOrAbsent(workspaceId, accountId);
        validateDetails(workspace, settings, submission.details(), accountId);
        var linkIds = catalog.requiredIdentityLinks(workspace, settings, accountId);
        var request = new WorkspaceAccessRequest();
        request.setWorkspace(workspace);
        request.setAccountId(accountId);
        request.setSubmittedAt(clock.instant());
        request.setPolicyVersion(policy.getVersion());
        request.setPolicySnapshot(settings);
        request.setSubmission(new WorkspaceAccessRequest.Submission(
                submission.details(), linkIds, List.copyOf(noticeKeys), submission.comments()));
        var previous = requests.findByWorkspace_IdAndAccountIdOrderBySubmittedAtDescIdDesc(
                workspaceId, accountId, PageRequest.of(0, 1));
        if (!previous.isEmpty()) {
            var predecessor = previous.getFirst();
            request.setSupersedesRequestId(predecessor.getId());
            if (predecessor.getStatus() == WorkspaceAccessRequest.Status.CHANGES_REQUESTED) {
                var before = snapshot(predecessor);
                predecessor.setStatus(WorkspaceAccessRequest.Status.SUPERSEDED);
                predecessor.setDecidedAt(clock.instant());
                auditChange(predecessor, before);
            }
        }
        requests.saveAndFlush(request);
        audit.record(ConfigAuditEntry.created(
                ConfigAuditEntityType.WORKSPACE_ACCESS_REQUEST, request.getId(), workspaceId, snapshot(request)));
        notifications.enqueue(request, WorkspaceAccessNotification.Kind.SUBMITTED);
        return view(request);
    }

    @Transactional
    public WorkspaceAccessRequestDTO decide(
            Long workspaceId, Long requestId, ReviewWorkspaceAccessRequestDTO decision) {
        var workspace = lock(workspaceId);
        Long reviewer = requireReviewer(workspaceId);
        var request = request(workspaceId, requestId);
        if (reviewer.equals(request.getAccountId())) throw forbidden("You cannot review your own access request");
        Stream.of(reviewer, request.getAccountId()).sorted().distinct().forEach(this::lockAccount);
        if (request.getVersion() != decision.version()
                || request.getStatus() != WorkspaceAccessRequest.Status.SUBMITTED) {
            throw conflict("This request changed or has already been reviewed; reload it before deciding");
        }
        var before = snapshot(request);
        switch (decision.decision()) {
            case APPROVE -> {
                var policy = enabledPolicy(workspaceId);
                if (policy.getVersion() != request.getPolicyVersion()) {
                    throw conflict("The policy changed after submission; ask the applicant to review it again");
                }
                if (!catalog.requiredIdentityLinks(workspace, policy.getSettings(), request.getAccountId())
                        .equals(request.getSubmission().identityLinkIds())) {
                    throw conflict("The applicant's required identity links changed; request a fresh submission");
                }
                var approved = decision.details();
                if (approved == null)
                    throw conflict("Approval requires the final maintainer, teams and access end date");
                validateDetails(workspace, policy.getSettings(), approved, request.getAccountId());
                grant(workspace, request, approved);
                request.setApprovedDetails(approved);
                request.setStatus(WorkspaceAccessRequest.Status.APPROVED);
            }
            case REQUEST_CHANGES -> {
                requireComment(decision.comment());
                request.setStatus(WorkspaceAccessRequest.Status.CHANGES_REQUESTED);
            }
            case REJECT -> {
                requireComment(decision.comment());
                request.setStatus(WorkspaceAccessRequest.Status.REJECTED);
            }
        }
        request.setDecidedAt(clock.instant());
        request.setDecidedByAccountId(reviewer);
        request.setDecisionComment(decision.comment());
        requests.flush();
        auditChange(request, before);
        notifications.enqueue(request, WorkspaceAccessNotification.Kind.DECIDED);
        return view(request);
    }

    @Transactional
    public WorkspaceAccessRequestDTO cancel(Long workspaceId, Long requestId) {
        lock(workspaceId);
        Long accountId = accountId();
        lockAccount(accountId);
        var request = request(workspaceId, requestId);
        if (!accountId.equals(request.getAccountId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (request.getStatus() != WorkspaceAccessRequest.Status.SUBMITTED
                && request.getStatus() != WorkspaceAccessRequest.Status.CHANGES_REQUESTED) {
            throw conflict("Only an undecided request can be withdrawn");
        }
        var before = snapshot(request);
        request.setStatus(WorkspaceAccessRequest.Status.CANCELLED);
        request.setDecidedAt(clock.instant());
        requests.flush();
        auditChange(request, before);
        return view(request);
    }

    private void grant(Workspace workspace, WorkspaceAccessRequest request, WorkspaceAccessDetails approved) {
        var existing = requireRequestManagedOrAbsent(workspace.getId(), request.getAccountId());
        var before = existing == null ? null : AccountMembershipSnapshot.of(existing);
        var membership = existing == null ? new WorkspaceAccountMembership() : existing;
        membership.setWorkspace(workspace);
        membership.setAccountId(request.getAccountId());
        membership.setRole(WorkspaceRole.MEMBER);
        membership.setSource(WorkspaceAccountMembership.Source.REQUEST);
        membership.setExpiresAt(approved.expiresAt());
        membership.setAccessRequestId(request.getId());
        memberships.save(membership);
        if (before == null)
            audit.record(ConfigAuditEntry.created(
                    ConfigAuditEntityType.WORKSPACE_ROLE,
                    request.getAccountId(),
                    workspace.getId(),
                    AccountMembershipSnapshot.of(membership)));
        else
            audit.record(ConfigAuditEntry.updated(
                    ConfigAuditEntityType.WORKSPACE_ROLE,
                    request.getAccountId(),
                    workspace.getId(),
                    before,
                    AccountMembershipSnapshot.of(membership)));
    }

    private @Nullable WorkspaceAccountMembership requireRequestManagedOrAbsent(Long workspaceId, Long accountId) {
        var membership = memberships
                .findByWorkspace_IdAndAccountId(workspaceId, accountId)
                .orElse(null);
        if (membership != null
                && (membership.isSuspended() || membership.getSource() != WorkspaceAccountMembership.Source.REQUEST)) {
            throw conflict(
                    "This account's workspace access is managed separately or suspended; an access request cannot override it");
        }
        return membership;
    }

    private void validateDetails(
            Workspace workspace,
            WorkspaceAccessPolicySettings settings,
            WorkspaceAccessDetails details,
            Long applicantId) {
        Instant now = clock.instant();
        if (!details.expiresAt().isAfter(now)
                || details.expiresAt().isAfter(now.plus(Duration.ofDays(settings.maximumDurationDays())))) {
            throw conflict("Choose a future access end date within the workspace's maximum duration");
        }
        if (new HashSet<>(details.teamIds()).size() != details.teamIds().size()
                || !settings.requestableTeamIds().containsAll(details.teamIds())) {
            throw conflict("Choose only the workspace's requestable teams, without duplicates");
        }
        catalog.teamOptions(workspace, settings);
        if (details.maintainerAccountId().equals(applicantId))
            throw conflict("Choose a responsible maintainer other than yourself");
        if (catalog.maintainers(workspace, settings).stream()
                .noneMatch(maintainer -> maintainer.accountId().equals(details.maintainerAccountId()))) {
            throw conflict("Choose an active maintainer from the configured maintainer team");
        }
    }

    private Workspace lock(Long workspaceId) {
        return workspaces
                .findByIdForUpdate(workspaceId)
                .filter(workspace -> workspace.getStatus() == Workspace.WorkspaceStatus.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private WorkspaceAccessRequest request(Long workspaceId, Long requestId) {
        return requests.findByIdAndWorkspace_Id(requestId, workspaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private WorkspaceAccessPolicy enabledPolicy(Long workspaceId) {
        return policies.findByWorkspace_Id(workspaceId)
                .filter(WorkspaceAccessPolicy::isEnabled)
                .orElseThrow(() -> conflict("This workspace is not accepting access requests"));
    }

    private void lockAccount(Long accountId) {
        identities
                .accountForUpdate(accountId)
                .filter(AccountIdentityQuery.AccountView::active)
                .orElseThrow(() -> conflict("An account involved in this request is no longer active"));
    }

    private Long requireReviewer(Long workspaceId) {
        Long id = accountId();
        if (memberships
                .findByWorkspace_IdAndAccountId(workspaceId, id)
                .filter(member ->
                        member.isActiveAt(clock.instant()) && member.getRole().isAtLeast(WorkspaceRole.ADMIN))
                .isEmpty()) {
            throw forbidden("Only an active workspace admin or owner can review access requests");
        }
        return id;
    }

    private static Long accountId() {
        return SecurityUtils.getCurrentAccountId()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    private static void requireComment(@Nullable String comment) {
        if (comment == null || comment.isBlank())
            throw conflict("Explain the rejection or the changes the applicant needs to make");
    }

    private void auditChange(WorkspaceAccessRequest request, RequestSnapshot before) {
        audit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.WORKSPACE_ACCESS_REQUEST,
                request.getId(),
                request.getWorkspace().getId(),
                before,
                snapshot(request)));
    }

    private static RequestSnapshot snapshot(WorkspaceAccessRequest request) {
        return new RequestSnapshot(
                request.getAccountId(),
                request.getStatus().name(),
                request.getPolicyVersion(),
                request.getApprovedDetails());
    }

    private WorkspaceAccessRequestDTO view(WorkspaceAccessRequest request) {
        var membership = memberships
                .findByWorkspace_IdAndAccountId(request.getWorkspace().getId(), request.getAccountId())
                .filter(member -> Objects.equals(member.getAccessRequestId(), request.getId()))
                .orElse(null);
        return new WorkspaceAccessRequestDTO(
                request.getId(),
                request.getVersion(),
                request.getAccountId(),
                identities
                        .account(request.getAccountId())
                        .map(AccountIdentityQuery.AccountView::displayName)
                        .orElse("Deleted account"),
                request.getStatus(),
                request.getSubmittedAt(),
                request.getPolicyVersion(),
                request.getSubmission().details(),
                request.getSubmission().comments(),
                request.getApprovedDetails(),
                request.getDecisionComment(),
                request.getDecidedAt(),
                membership != null && membership.isActiveAt(clock.instant()),
                membership == null ? null : membership.getExpiresAt());
    }

    private static ResponseStatusException conflict(String message) {
        return WorkspaceAccessCatalog.conflict(message);
    }

    private static ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }

    record SubmitWorkspaceAccessRequestDTO(
            long policyVersion,
            @AssertTrue boolean introductionAcknowledged,
            @NonNull @NotNull @Size(max = 8) List<@NotNull String> acknowledgedNoticeKeys,
            @NonNull @NotNull @Valid WorkspaceAccessDetails details,
            @Nullable @Size(max = 4000) String comments) {}

    enum Decision {
        APPROVE,
        REQUEST_CHANGES,
        REJECT
    }

    record ReviewWorkspaceAccessRequestDTO(
            long version,
            @NonNull @NotNull Decision decision,
            @Nullable @Valid WorkspaceAccessDetails details,
            @Nullable @Size(max = 4000) String comment) {}

    record WorkspaceAccessRequestDTO(
            @NonNull Long id,
            long version,
            @NonNull Long accountId,
            @NonNull String displayName,
            WorkspaceAccessRequest.@NonNull Status status,
            @NonNull Instant submittedAt,
            long policyVersion,
            @NonNull WorkspaceAccessDetails requestedDetails,
            @Nullable String comments,
            @Nullable WorkspaceAccessDetails approvedDetails,
            @Nullable String decisionComment,
            @Nullable Instant decidedAt,
            boolean accessActive,
            @Nullable Instant effectiveExpiresAt) {}

    record RequestSnapshot(
            Long accountId,
            String status,
            long policyVersion,
            @Nullable WorkspaceAccessDetails approvedDetails) implements ConfigAuditSnapshot {}
}
