package de.tum.cit.aet.hephaestus.notification.push;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.NativeSessionQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceMemberQuery;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceSummaryQuery;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends queued push notifications and settles their receipts.
 *
 * <p>Everything that decides whether a notification may still go out is checked when it is sent, not when
 * it was queued: the native session that registered the device is still signed in with current consent,
 * the developer is still linked to that account and still a member of the workspace. A notification that
 * fails a check is suppressed with the reason, never sent late.
 *
 * <p>Delivery is at least once. A sender that dies between Expo accepting a batch and recording the
 * tickets lets its claim lapse, and the batch goes out again; a repeated nudge is the lesser harm next to
 * a lost one.
 */
@ConditionalOnServerRole
@Component
@WorkspaceAgnostic("The sender drains every workspace's queue; each row carries its workspace_id")
public class PushDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PushDispatcher.class);

    private static final int BATCH = 200;
    private static final Duration CLAIM = Duration.ofMinutes(2);
    private static final int MAX_ATTEMPTS = 5;

    /** Expo recommends reading receipts about 15 minutes after sending, and keeps them for a day. */
    private static final Duration RECEIPT_DELAY = Duration.ofMinutes(15);

    private static final Duration RECEIPT_TTL = Duration.ofHours(24);
    private static final Duration RETENTION = Duration.ofDays(30);

    /** Expo's ticket and receipt error for an installation that no longer accepts notifications. */
    private static final String DEVICE_NOT_REGISTERED = "DeviceNotRegistered";

    /** The Android notification channel the app creates; iOS ignores it. */
    static final String CHANNEL_ID = "practice-feedback";

    private final PushNotificationRepository notificationRepository;
    private final PushDeviceRepository deviceRepository;
    private final ExpoPushClient expoPushClient;
    private final PushProperties properties;
    private final NativeSessionQuery nativeSessionQuery;
    private final AccountWorkspaceMembershipQuery memberships;
    private final WorkspaceMemberQuery workspaceMemberQuery;
    private final WorkspaceSummaryQuery workspaceSummaryQuery;
    private final Clock clock;

    public PushDispatcher(
            PushNotificationRepository notificationRepository,
            PushDeviceRepository deviceRepository,
            ExpoPushClient expoPushClient,
            PushProperties properties,
            NativeSessionQuery nativeSessionQuery,
            AccountWorkspaceMembershipQuery memberships,
            WorkspaceMemberQuery workspaceMemberQuery,
            WorkspaceSummaryQuery workspaceSummaryQuery,
            Clock clock) {
        this.notificationRepository = notificationRepository;
        this.deviceRepository = deviceRepository;
        this.expoPushClient = expoPushClient;
        this.properties = properties;
        this.nativeSessionQuery = nativeSessionQuery;
        this.memberships = memberships;
        this.workspaceMemberQuery = workspaceMemberQuery;
        this.workspaceSummaryQuery = workspaceSummaryQuery;
        this.clock = clock;
    }

    private record Outgoing(PushNotification notification, PushDevice device, ExpoPushClient.Message message) {}

    @Scheduled(fixedDelay = 15, initialDelay = 30, timeUnit = TimeUnit.SECONDS)
    @SchedulerLock(name = "push-dispatch", lockAtMostFor = "PT2M")
    public void sendDue() {
        Instant now = clock.instant();
        List<Outgoing> outgoing = new ArrayList<>();
        for (PushNotification notification : notificationRepository.findDue(now, PageRequest.of(0, BATCH))) {
            if (notificationRepository.claim(
                            notification.getId(),
                            notification.getWorkspaceId(),
                            now,
                            now.plus(CLAIM),
                            notification.getVersion())
                    != 1) {
                continue;
            }
            notification.setVersion(notification.getVersion() + 1);
            notification.setAttempts(notification.getAttempts() + 1);
            if (notification.getAttempts() > MAX_ATTEMPTS) {
                settle(notification, PushNotification.State.FAILED, "attempts_exhausted", now);
                continue;
            }
            String suppression = properties.available() ? null : "push_unavailable";
            PushDevice device =
                    deviceRepository.findById(notification.getPushDeviceId()).orElse(null);
            if (suppression == null) {
                suppression = device == null ? "device_gone" : ineligibility(notification, device);
            }
            if (suppression != null || device == null) {
                settle(notification, PushNotification.State.SUPPRESSED, suppression, now);
                continue;
            }
            Optional<WorkspaceSummaryQuery.WorkspaceSummary> workspace =
                    workspaceSummaryQuery.findById(notification.getWorkspaceId());
            if (workspace.isEmpty()) {
                settle(notification, PushNotification.State.SUPPRESSED, "workspace_gone", now);
                continue;
            }
            notification.setSentDeviceVersion(device.getVersion());
            outgoing.add(new Outgoing(notification, device, message(device, workspace.get())));
        }
        for (int from = 0; from < outgoing.size(); from += ExpoPushClient.MAX_MESSAGES) {
            send(outgoing.subList(from, Math.min(outgoing.size(), from + ExpoPushClient.MAX_MESSAGES)), now);
        }
    }

    /** Why the notification may not go out any more, or null when it may. */
    @Nullable
    private String ineligibility(PushNotification notification, PushDevice device) {
        if (!nativeSessionQuery.isSignedIn(device.getNativeSessionId(), device.getAccountId())) {
            return "session_or_consent_ended";
        }
        if (!workspaceMemberQuery.isMember(notification.getWorkspaceId(), notification.getRecipientUserId())) {
            return "not_a_member";
        }
        Optional<Long> recipientAccount = memberships.activeAccountIdForMember(
                notification.getWorkspaceId(), notification.getRecipientUserId());
        if (recipientAccount.isEmpty() || !recipientAccount.get().equals(device.getAccountId())) {
            return "recipient_changed";
        }
        return null;
    }

    /**
     * The whole message. It names neither the practice nor the work nor the feedback: a lock screen is not
     * a private surface, and the text belongs to the practice page that delivers it.
     */
    private static ExpoPushClient.Message message(PushDevice device, WorkspaceSummaryQuery.WorkspaceSummary workspace) {
        return new ExpoPushClient.Message(
                device.getExpoPushToken(),
                "New practice feedback",
                "Open Hephaestus to read it.",
                Map.of(
                        "kind",
                        "practice-feedback",
                        "workspaceSlug",
                        workspace.slug(),
                        "nativeSessionId",
                        device.getNativeSessionId().toString()),
                "default",
                CHANNEL_ID,
                "default");
    }

    private void send(List<Outgoing> batch, Instant now) {
        ExpoPushClient.SendOutcome outcome =
                expoPushClient.send(batch.stream().map(Outgoing::message).toList());
        switch (outcome) {
            case ExpoPushClient.Accepted accepted -> {
                for (int i = 0; i < batch.size(); i++) {
                    record(batch.get(i), accepted.tickets().get(i), now);
                }
            }
            case ExpoPushClient.Rejected rejected -> {
                log.warn("Expo push rejected a batch of {}: {}", batch.size(), rejected.reason());
                for (Outgoing item : batch) {
                    PushNotification notification = item.notification();
                    if (rejected.retryable() && notification.getAttempts() < MAX_ATTEMPTS) {
                        release(notification, now);
                    } else {
                        settle(notification, PushNotification.State.FAILED, rejected.reason(), now);
                    }
                }
            }
        }
    }

    private void record(Outgoing item, ExpoPushClient.Ticket ticket, Instant now) {
        PushNotification notification = item.notification();
        if (ticket.accepted()) {
            notification.setState(PushNotification.State.SENT);
            notification.setTicketId(ticket.id());
            notification.setSentAt(now);
            save(notification);
            return;
        }
        String error = ticket.error();
        if ("MessageRateExceeded".equals(error) && notification.getAttempts() < MAX_ATTEMPTS) {
            release(notification, now);
            return;
        }
        settle(notification, PushNotification.State.FAILED, error != null ? error : "ticket_error", now);
        if (DEVICE_NOT_REGISTERED.equals(error)) {
            deviceRepository.deleteRegistration(
                    item.device().getId(), item.device().getVersion());
        }
    }

    @Scheduled(fixedDelay = 5, initialDelay = 5, timeUnit = TimeUnit.MINUTES)
    @SchedulerLock(name = "push-receipts", lockAtMostFor = "PT5M")
    public void settleReceipts() {
        if (!properties.available()) {
            return;
        }
        Instant now = clock.instant();
        List<PushNotification> sent = notificationRepository.findAwaitingReceipt(
                now.minus(RECEIPT_DELAY), PageRequest.of(0, ExpoPushClient.MAX_RECEIPT_IDS));
        if (sent.isEmpty()) {
            return;
        }
        List<String> ids = sent.stream()
                .map(PushNotification::getTicketId)
                .filter(Objects::nonNull)
                .toList();
        Map<String, ExpoPushClient.Receipt> receipts = expoPushClient.receipts(ids);
        for (PushNotification notification : sent) {
            ExpoPushClient.Receipt receipt =
                    notification.getTicketId() == null ? null : receipts.get(notification.getTicketId());
            Instant sentAt = notification.getSentAt();
            if (receipt == null) {
                if (sentAt == null || sentAt.plus(RECEIPT_TTL).isBefore(now)) {
                    settle(notification, PushNotification.State.FAILED, "receipt_unavailable", now);
                }
                continue;
            }
            if ("ok".equals(receipt.status())) {
                settle(notification, PushNotification.State.DELIVERED, null, now);
                continue;
            }
            String error = receipt.error();
            if ("MessageRateExceeded".equals(error) && notification.getAttempts() < MAX_ATTEMPTS) {
                release(notification, now);
                continue;
            }
            settle(notification, PushNotification.State.FAILED, error != null ? error : "receipt_error", now);
            Long registration = notification.getSentDeviceVersion();
            if (DEVICE_NOT_REGISTERED.equals(error) && registration != null) {
                deviceRepository.deleteRegistration(notification.getPushDeviceId(), registration);
            }
        }
    }

    @Scheduled(cron = "0 45 3 * * *")
    @SchedulerLock(name = "push-retention", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void prune() {
        int deleted = notificationRepository.deleteCreatedBefore(clock.instant().minus(RETENTION));
        log.info("PushDispatcher: pruned {} push notifications past retention", deleted);
    }

    private void save(PushNotification notification) {
        try {
            notificationRepository.saveAndFlush(notification);
        } catch (OptimisticLockingFailureException e) {
            // A newer lease owns completion, or erasure removed the row while Expo was responding.
            log.debug("Push notification {} no longer belongs to this sender", notification.getId());
        }
    }

    private void release(PushNotification notification, Instant now) {
        long backoffMinutes = 1L << Math.min(notification.getAttempts(), 6);
        notification.setState(PushNotification.State.PENDING);
        notification.setTicketId(null);
        notification.setSentAt(null);
        notification.setSentDeviceVersion(null);
        notification.setNextAttemptAt(now.plus(Duration.ofMinutes(backoffMinutes)));
        save(notification);
    }

    private void settle(
            PushNotification notification, PushNotification.State state, @Nullable String reason, Instant now) {
        notification.setState(state);
        notification.setFailureReason(reason);
        notification.setSettledAt(now);
        save(notification);
    }
}
