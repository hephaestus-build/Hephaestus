package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.handler.ProviderEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The provider event inbox in PostgreSQL. A claim leases the oldest open event of a scope with {@code SKIP LOCKED}, on
 * the database clock, fenced by the event's lease epoch, as {@code agent_run} does. A scope is one workspace and one
 * subject without its event token, so the events of one repository, connection, team or subscription run in order and
 * the scopes run side by side. Each statement runs in its caller's transaction, or in its own.
 */
@Component
@ConditionalOnServerRole
public class ProviderEventStore {

    private static final TypeReference<Map<String, String>> HEADERS = new TypeReference<>() {};

    private final ProviderEventRepository events;
    private final ObjectMapper mapper;
    private final ProviderEventNotifications notifications;
    private final PersonDataCopyFence copyFence;

    public ProviderEventStore(
            ProviderEventRepository events,
            ObjectMapper mapper,
            ProviderEventNotifications notifications,
            PersonDataCopyFence copyFence) {
        this.events = events;
        this.mapper = mapper;
        this.notifications = notifications;
        this.copyFence = copyFence;
    }

    /** A delivery for one workspace, or for the installation when {@code workspaceId} is null. */
    @SuppressWarnings("ArrayRecordComponent") // Written once into the row; no caller compares arrivals.
    public record Arrival(
            @Nullable Long workspaceId,
            String provider,
            String subject,
            String deliveryKey,
            Map<String, String> headers,
            byte[] payload) {}

    /**
     * A leased event: what the handler reads, and the epoch that fences its outcome. {@code attempt} counts its claims, also the ones that failed for an operational reason;
     * {@code failures} counts only the failed attempts that spend the poison budget.
     */
    public record Claimed(
            long id, @Nullable Long workspaceId, ProviderEvent event, int attempt, int failures, int leaseEpoch) {}

    /** A failed event, as the dead-letter API shows it. */
    public record DeadLetter(
            long id,
            @Nullable Long workspaceId,
            String provider,
            String errorClass,
            Instant receivedAt,
            Instant failedAt,
            @Nullable Instant replayedAt,
            @Nullable Long replayedBy) {}

    /**
     * Stores each arrival once: a delivery that a provider sends again finds its row and adds none. The notification
     * reaches the claimers when the caller's transaction commits.
     *
     * @return the number of new rows
     */
    @Transactional
    @WorkspaceAgnostic(
            "The receiver stores one row for each workspace that the delivery reaches, and installation rows")
    public int store(List<Arrival> arrivals) {
        if (!arrivals.isEmpty() && !copyFence.tryHoldForCapture()) {
            throw new TransientDataAccessResourceException("Person erasure is in progress");
        }
        int stored = 0;
        for (Arrival arrival : arrivals) {
            stored += events.store(new ProviderEventRepository.NewEvent(
                    arrival.workspaceId(),
                    arrival.provider(),
                    arrival.subject(),
                    scopeKey(arrival.workspaceId(), arrival.subject()),
                    arrival.deliveryKey(),
                    mapper.writeValueAsString(arrival.headers()),
                    arrival.payload()));
        }
        if (stored > 0) {
            notifications.queuedAfterCommit();
        }
        return stored;
    }

    /** Bounds each later statement of the caller's transaction, so a blocked database fails it instead of holding it. */
    @Transactional
    @WorkspaceAgnostic("Sets a bound of the current transaction; it reads no table")
    public void boundStatements(Duration timeout) {
        events.boundStatements(timeout.toMillis() + "ms");
    }

    /**
     * Leases the oldest due event whose scope has no leased event and no older open one. A failed event waits for its
     * retry at the head of its scope, so the scope keeps its order. The events of a suspended workspace wait until it
     * is active again.
     */
    @Transactional
    @WorkspaceAgnostic("The claim offers the queued events of every workspace and of the installation")
    public Optional<Claimed> claim(Duration lease) {
        return events.claim(lease.toSeconds())
                .map(row -> new Claimed(
                        row.getId(),
                        row.getWorkspaceId(),
                        new ProviderEvent(
                                row.getSubject(),
                                mapper.readValue(row.getHeaders(), HEADERS),
                                row.getPayload(),
                                row.getReceivedAt()),
                        row.getAttempt(),
                        row.getFailures(),
                        row.getLeaseEpoch()));
    }

    /** Ends a leased event; false when its lease was taken back meanwhile. */
    @Transactional
    @WorkspaceAgnostic("The event id and its lease epoch identify the claimed row")
    public boolean complete(long id, int leaseEpoch) {
        return events.complete(id, leaseEpoch) == 1;
    }

    /** Returns a leased event to its scope's head, due after {@code delay}. */
    @Transactional
    @WorkspaceAgnostic("The event id and its lease epoch identify the claimed row")
    public boolean retry(long id, int leaseEpoch, Duration delay, boolean counted) {
        return events.retry(id, leaseEpoch, delay.toMillis() / 1000.0, counted) == 1;
    }

    /** Ends a leased event as a dead letter. It keeps its payload, so a replay needs nothing else. */
    @Transactional
    @WorkspaceAgnostic("The event id and its lease epoch identify the claimed row")
    public boolean dead(long id, int leaseEpoch, String errorClass) {
        return events.dead(id, leaseEpoch, errorClass.length() <= 255 ? errorClass : errorClass.substring(0, 255)) == 1;
    }

    /** Extends a matching lease without waiting on a write fence; false when the row is locked or the lease changed. */
    @Transactional
    @WorkspaceAgnostic("The event id and its lease epoch identify the claimed row")
    public boolean renew(long id, int leaseEpoch, Duration lease) {
        return events.renew(id, leaseEpoch, lease.toSeconds()) == 1;
    }

    /** Gives back a lease that a stopping server could not finish; the attempt does not count. */
    @Transactional
    @WorkspaceAgnostic("The event id and its lease epoch identify the claimed row")
    public boolean release(long id, int leaseEpoch) {
        return events.release(id, leaseEpoch) == 1;
    }

    /**
     * Takes back the leases that expired: the server that held them stopped. An event that used its attempts becomes a
     * dead letter, so an event that stops each server that handles it cannot stop them all in turn.
     *
     * @return the number of events taken back
     */
    @Transactional
    @WorkspaceAgnostic("The reaper takes back the expired leases of every workspace")
    public int reapExpired(int maxAttempts) {
        return events.reapExpired(maxAttempts);
    }

    /** Records a handler step in the caller's transaction; false when an earlier attempt committed it. */
    @Transactional
    @WorkspaceAgnostic("The event id identifies the claimed row")
    public boolean claimStep(long eventId, int leaseEpoch, String step) {
        holdWriteFence(eventId, leaseEpoch);
        return events.claimStep(eventId, step) == 1;
    }

    /** Fences writes that use natural keys rather than a named delivery step, in the same transaction. */
    @Transactional
    @WorkspaceAgnostic("The event id and its lease epoch identify the claimed row")
    public void fenceStep(long eventId, int leaseEpoch) {
        holdWriteFence(eventId, leaseEpoch);
    }

    private void holdWriteFence(long eventId, int leaseEpoch) {
        // A caller can already hold a domain lock. Never wait on erasure while holding that lock: roll back,
        // then the consumer checks the lease in a fresh transaction after erasure releases its copy fence.
        if (!copyFence.tryHoldForCapture()) {
            throw new TransientDataAccessResourceException("Person erasure is in progress");
        }
        checkLease(events.lockLease(eventId), leaseEpoch);
    }

    /** Checks an outcome after the handler transaction ended, before retrying or recording a drop. */
    @Transactional
    @WorkspaceAgnostic("The event id and its lease epoch identify the claimed row")
    public void assertLease(long eventId, int leaseEpoch) {
        checkLease(events.awaitLease(eventId), leaseEpoch);
    }

    private static void checkLease(Optional<ProviderEventRepository.LeaseRow> candidate, int leaseEpoch) {
        var row = candidate.orElseThrow(
                () -> new ProviderEventFenceException(ProviderEventFenceException.Reason.ERASED_DURING_PROCESSING));
        if (!"LEASED".equals(row.getState()) || row.getLeaseEpoch() != leaseEpoch) {
            throw new ProviderEventFenceException(ProviderEventFenceException.Reason.LEASE_LOST);
        }
    }

    @Transactional(readOnly = true)
    @WorkspaceAgnostic("The event id identifies the claimed row")
    public boolean hasStep(long eventId, String step) {
        return events.hasStep(eventId, step);
    }

    /** Deletes the handled events that ended before {@code doneBefore}, and the dead ones before {@code deadBefore}. */
    @Transactional
    @WorkspaceAgnostic("Retention removes the ended events of every workspace")
    public int deleteEnded(Instant doneBefore, Instant deadBefore) {
        return events.deleteEnded(doneBefore, deadBefore);
    }

    /** Deletes the events of one workspace, in the caller's transaction; their steps go with them. */
    @Transactional
    public int deleteWorkspace(long workspaceId) {
        List<Long> erased = events.deleteWorkspace(workspaceId);
        DeliveryInbox.erasedByHandler(erased);
        return erased.size();
    }

    /**
     * Deletes every event of one provider resource, including leased events, in the caller's transaction.
     * Later write steps and completion are fenced by the event row and its lease epoch.
     */
    @Transactional
    public int deleteResource(long workspaceId, String provider, String resource) {
        List<Long> erased = events.deleteResource(workspaceId, provider, resource);
        DeliveryInbox.erasedByHandler(erased);
        return erased.size();
    }

    /** The dead letters of one workspace, or of the installation when {@code workspaceId} is null, after {@code idAfter}. */
    @Transactional(readOnly = true)
    @WorkspaceAgnostic("The workspace is bound explicitly; null selects only installation rows")
    public List<DeadLetter> deadLetters(@Nullable Long workspaceId, long idAfter, int limit) {
        return events.deadLetters(workspaceId, idAfter, limit).stream()
                .map(ProviderEventStore::deadLetter)
                .toList();
    }

    /**
     * Queues a dead letter again, as its first attempt. The steps it committed stay, so its handler skips them.
     *
     * @return the replayed event, or empty when no dead letter of that workspace has the id
     */
    @Transactional
    @WorkspaceAgnostic("The workspace is bound explicitly; null selects only installation rows")
    public Optional<DeadLetter> replay(@Nullable Long workspaceId, long id, long actor) {
        Optional<DeadLetter> replayed = events.replay(workspaceId, id, actor).map(ProviderEventStore::deadLetter);
        replayed.ifPresent(ignored -> notifications.queuedAfterCommit());
        return replayed;
    }

    /** One workspace and one subject without its event token. */
    static String scopeKey(@Nullable Long workspaceId, String subject) {
        if (workspaceId == null && ConsumerSubjectMath.isInstallationControlSubject(subject)) {
            return "installation:github.?.?";
        }
        int lastDot = subject.lastIndexOf('.');
        String resource = lastDot > 0 ? subject.substring(0, lastDot) : subject;
        return (workspaceId == null ? "installation" : workspaceId.toString()) + ":" + resource;
    }

    private static DeadLetter deadLetter(ProviderEventRepository.DeadRow row) {
        return new DeadLetter(
                row.getId(),
                row.getWorkspaceId(),
                row.getProvider(),
                row.getErrorClass(),
                row.getReceivedAt(),
                row.getFailedAt(),
                row.getReplayedAt(),
                row.getReplayedBy());
    }
}
