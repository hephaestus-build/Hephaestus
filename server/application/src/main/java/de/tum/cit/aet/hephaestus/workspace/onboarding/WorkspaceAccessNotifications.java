package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.email.SmtpEmailGateway;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembershipRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@ConditionalOnServerRole
class WorkspaceAccessNotifications {
    private final WorkspaceAccessNotificationRepository notifications;
    private final WorkspaceAccountMembershipRepository memberships;
    private final AccountContactQuery contacts;
    private final SmtpEmailGateway email;
    private final Clock clock;

    private final String webappUrl;

    WorkspaceAccessNotifications(
            WorkspaceAccessNotificationRepository notifications,
            WorkspaceAccountMembershipRepository memberships,
            AccountContactQuery contacts,
            SmtpEmailGateway email,
            Clock clock,
            @Value("${hephaestus.webapp.url}") String webappUrl) {
        this.notifications = notifications;
        this.memberships = memberships;
        this.contacts = contacts;
        this.email = email;
        this.clock = clock;
        this.webappUrl = webappUrl;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(WorkspaceAccessRequest request, WorkspaceAccessNotification.Kind kind) {
        if (notifications.existsByWorkspace_IdAndRequest_IdAndKind(
                request.getWorkspace().getId(), request.getId(), kind)) return;
        var notification = new WorkspaceAccessNotification();
        notification.setWorkspace(request.getWorkspace());
        notification.setRequest(request);
        notification.setKind(kind);
        notification.setNextAttemptAt(clock.instant());
        notifications.save(notification);
    }

    @Transactional
    public void deliver(Long workspaceId, Long id) {
        var notification = notifications.lockInWorkspace(workspaceId, id).orElse(null);
        Instant now = clock.instant();
        if (notification == null
                || notification.getState() != WorkspaceAccessNotification.State.PENDING
                || notification.getNextAttemptAt().isAfter(now)) return;
        if (stale(notification)) {
            notification.setState(WorkspaceAccessNotification.State.CANCELLED);
            notification.setReason(WorkspaceAccessNotification.Reason.REQUEST_REPLACED);
            return;
        }
        var request = notification.getRequest();
        var recipient = notification.getKind() == WorkspaceAccessNotification.Kind.SUBMITTED
                ? request.getPolicySnapshot().adminMailbox()
                : contacts.verifiedEmail(request.getAccountId()).orElse(null);
        if (recipient == null) {
            notification.setState(WorkspaceAccessNotification.State.FAILED);
            notification.setReason(WorkspaceAccessNotification.Reason.NO_VERIFIED_CONTACT);
            return;
        }
        switch (email.send(recipient, "Hephaestus workspace access update", body(notification))) {
            case SENT -> {
                notification.setAttempts(notification.getAttempts() + 1);
                notification.setState(WorkspaceAccessNotification.State.SENT);
                notification.setSentAt(now);
                notification.setReason(null);
            }
            case SUPPRESSED ->
                delay(notification, WorkspaceAccessNotification.Reason.SILENT_MODE, Duration.ofMinutes(5));
            case NOT_CONFIGURED ->
                delay(notification, WorkspaceAccessNotification.Reason.MAIL_NOT_CONFIGURED, Duration.ofHours(1));
            case INVALID_ADDRESS -> {
                notification.setState(WorkspaceAccessNotification.State.FAILED);
                notification.setReason(WorkspaceAccessNotification.Reason.INVALID_CONTACT);
            }
            case FAILED -> {
                notification.setAttempts(notification.getAttempts() + 1);
                delay(
                        notification,
                        WorkspaceAccessNotification.Reason.DELIVERY_FAILED,
                        Duration.ofMinutes(Math.min(60, 1L << Math.min(6, notification.getAttempts() - 1))));
                if (notification.getAttempts() >= 10) notification.setState(WorkspaceAccessNotification.State.FAILED);
            }
        }
    }

    @Transactional(readOnly = true)
    public List<WorkspaceAccessNotificationDTO> list(Long workspaceId, Long requestId) {
        return notifications.findByWorkspace_IdAndRequest_IdOrderById(workspaceId, requestId).stream()
                .map(WorkspaceAccessNotifications::view)
                .toList();
    }

    @Transactional
    public WorkspaceAccessNotificationDTO retry(Long workspaceId, Long id) {
        var notification = notifications
                .lockInWorkspace(workspaceId, id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (notification.getState() == WorkspaceAccessNotification.State.SENT
                || notification.getState() == WorkspaceAccessNotification.State.CANCELLED) {
            throw WorkspaceAccessCatalog.conflict("A completed notification cannot be retried");
        }
        notification.setState(WorkspaceAccessNotification.State.PENDING);
        notification.setNextAttemptAt(clock.instant());
        return view(notification);
    }

    private boolean stale(WorkspaceAccessNotification notification) {
        var request = notification.getRequest();
        return switch (notification.getKind()) {
            case SUBMITTED -> request.getStatus() != WorkspaceAccessRequest.Status.SUBMITTED;
            case DECIDED ->
                request.getStatus() != WorkspaceAccessRequest.Status.APPROVED
                        && request.getStatus() != WorkspaceAccessRequest.Status.REJECTED
                        && request.getStatus() != WorkspaceAccessRequest.Status.CHANGES_REQUESTED;
            case REMINDER, EXPIRED -> {
                var membership = memberships
                        .findByWorkspace_IdAndAccountId(request.getWorkspace().getId(), request.getAccountId())
                        .orElse(null);
                yield membership == null
                        || !Objects.equals(membership.getAccessRequestId(), request.getId())
                        || (notification.getKind() == WorkspaceAccessNotification.Kind.REMINDER
                                && !membership.isActiveAt(clock.instant()))
                        || (notification.getKind() == WorkspaceAccessNotification.Kind.EXPIRED
                                && membership.isActiveAt(clock.instant()));
            }
        };
    }

    private String body(WorkspaceAccessNotification notification) {
        var request = notification.getRequest();
        var workspace = request.getWorkspace();
        String route = notification.getKind() == WorkspaceAccessNotification.Kind.SUBMITTED
                ? "admin/access"
                : "request-access";
        String link = UriComponentsBuilder.fromUriString(webappUrl)
                .pathSegment("w", workspace.getWorkspaceSlug())
                .path("/" + route)
                .queryParamIfPresent(
                        "renew",
                        notification.getKind() == WorkspaceAccessNotification.Kind.REMINDER
                                ? java.util.Optional.of(true)
                                : java.util.Optional.empty())
                .build()
                .encode()
                .toUriString();
        String explanation =
                switch (notification.getKind()) {
                    case SUBMITTED -> "A new access request is awaiting review.";
                    case DECIDED ->
                        switch (request.getStatus()) {
                            case APPROVED ->
                                "Your access request was approved. External organization invitations and team changes have their own delivery status.";
                            case CHANGES_REQUESTED ->
                                "A workspace administrator has asked you to update your access request.";
                            default ->
                                "Your access request was declined. You can read the administrator's explanation in Hephaestus.";
                        };
                    case REMINDER ->
                        "Your workspace access will expire soon. You can request a renewal; submitting it does not extend your current access.";
                    case EXPIRED ->
                        "Your workspace access has ended. External organization and team removal is tracked separately and may still be pending.";
                };
        return "Workspace: " + workspace.getDisplayName() + "\n\n" + explanation + "\n\n" + link + "\n";
    }

    private void delay(
            WorkspaceAccessNotification notification, WorkspaceAccessNotification.Reason reason, Duration delay) {
        notification.setReason(reason);
        notification.setNextAttemptAt(clock.instant().plus(delay));
    }

    private static WorkspaceAccessNotificationDTO view(WorkspaceAccessNotification notification) {
        return new WorkspaceAccessNotificationDTO(
                notification.getId(),
                notification.getKind(),
                notification.getState(),
                notification.getReason(),
                notification.getAttempts(),
                notification.getNextAttemptAt(),
                notification.getSentAt());
    }

    record WorkspaceAccessNotificationDTO(
            @NonNull Long id,
            WorkspaceAccessNotification.@NonNull Kind kind,
            WorkspaceAccessNotification.@NonNull State state,
            WorkspaceAccessNotification.@Nullable Reason reason,
            int attempts,
            @NonNull Instant nextAttemptAt,
            @Nullable Instant sentAt) {}
}
