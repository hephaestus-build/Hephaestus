package de.tum.cit.aet.hephaestus.integration.core.api;

import de.tum.cit.aet.hephaestus.integration.core.item.ItemRepository;
import de.tum.cit.aet.hephaestus.integration.core.itemevent.ItemEvent;
import de.tum.cit.aet.hephaestus.integration.core.itemevent.ItemEventLedgerProperties;
import de.tum.cit.aet.hephaestus.integration.core.itemevent.ItemEventRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventInput;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ItemEventService {
    private final ItemEventRepository repository;
    private final ItemRepository items;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    private final ItemEventLedgerProperties properties;

    @Transactional(readOnly = true)
    public Optional<ItemEventView> find(long workspaceId, UUID eventId) {
        return repository
                .findByIdAndWorkspaceId(eventId, workspaceId)
                .map(event -> new ItemEventView(
                        new ItemEventName(event.getName()),
                        event.getItemVersion(),
                        event.getState(),
                        event.getOccurredAt()));
    }

    public UUID record(ItemEventInput input) {
        var item = items.findByIdAndWorkspaceId(input.itemId(), input.workspaceId())
                .orElseThrow(() -> new IllegalArgumentException("Item does not belong to workspace"));
        if (!input.name().value().startsWith(item.getKind().value() + "."))
            throw new IllegalArgumentException("Event does not belong to item kind");
        UUID id = UUID.randomUUID();
        String version = input.itemVersion();
        String evidence = input.evidence();
        // Frozen at observation: no later decision, review or retry extends how long the record may be used.
        Instant evidenceExpiresAt = evidence == null ? null : clock.instant().plus(properties.retainFor());
        if (version == null) {
            var fence = Objects.requireNonNull(input.pendingFence());
            // No unique key covers a row with no version, so the item lock serializes the existence check.
            items.lockByIdAndWorkspaceId(input.itemId(), input.workspaceId());
            if (repository.insertUnresolvedIfAbsent(
                            id,
                            input.workspaceId(),
                            input.itemId(),
                            input.name().value(),
                            fence,
                            input.sourceKey(),
                            input.occurredAt(),
                            input.foundBy().name(),
                            input.actorUserId(),
                            clock.instant(),
                            evidence,
                            evidenceExpiresAt)
                    == 1) {
                publisher.publishEvent(new ItemEventRecorded(input.workspaceId(), id, input.itemId()));
                return id;
            }
            return repository
                    .findByWorkspaceIdAndItemIdAndNameAndItemVersionIsNullAndPendingFence(
                            input.workspaceId(), input.itemId(), input.name().value(), fence)
                    .or(() -> bySource(input))
                    .orElseThrow()
                    .getId();
        }
        int inserted = repository.insertIfAbsent(
                id,
                input.workspaceId(),
                input.itemId(),
                input.name().value(),
                version,
                input.sourceKey(),
                input.occurredAt(),
                input.foundBy().name(),
                input.actorUserId(),
                input.requestedById(),
                clock.instant(),
                evidence,
                evidenceExpiresAt);
        // A redelivered occasion keeps the evidence of its first record.
        if (inserted == 1) {
            publisher.publishEvent(new ItemEventRecorded(input.workspaceId(), id, input.itemId()));
            return id;
        }
        // The checks event repeats per version, one for each settled run set, so the source key decides first.
        var existing = bySource(input)
                .or(() -> repository.findByWorkspaceIdAndItemIdAndNameAndItemVersion(
                        input.workspaceId(), input.itemId(), input.name().value(), version))
                .orElseThrow();
        observeSource(input.workspaceId(), input.itemId(), input.name());
        return existing.getId();
    }

    /** The occasion an earlier delivery of the same provider event recorded, whatever version it named. */
    private Optional<ItemEvent> bySource(ItemEventInput input) {
        String sourceKey = input.sourceKey();
        return sourceKey == null
                ? Optional.empty()
                : repository.findByWorkspaceIdAndItemIdAndNameAndSourceKey(
                        input.workspaceId(), input.itemId(), input.name().value(), sourceKey);
    }

    /** Open occasions on the item that still have no pinned version, oldest first. Reads no retained record. */
    @Transactional(readOnly = true)
    public List<Unresolved> unresolved(long workspaceId, long itemId) {
        return repository.findUnresolved(workspaceId, itemId).stream()
                .map(event -> new Unresolved(
                        event.getId(),
                        new ItemEventName(event.getName()),
                        ItemEventState.valueOf(event.getState()),
                        Objects.requireNonNull(event.getPendingFence())))
                .toList();
    }

    public record Unresolved(UUID id, ItemEventName name, ItemEventState state, String pendingFence) {}

    /**
     * What an unresolved occasion retained, read once to complete it: its record until its expiry, an
     * {@link ItemEventInput#unretained} statement after it, or null when it retained nothing.
     */
    @Transactional(readOnly = true)
    public @Nullable String pendingEvidence(long workspaceId, UUID eventId) {
        return repository
                .findPendingEvidence(workspaceId, eventId)
                .filter(ItemEventRepository.EvidenceRow::getRetained)
                .map(row -> {
                    var retained = retained(row);
                    var record = retained.record();
                    return record != null
                            ? record
                            : ItemEventInput.unretained(Objects.requireNonNull(retained.unretained()));
                })
                .orElse(null);
    }

    /**
     * Pins the exact version an unresolved occasion now has and offers it for review. An occasion whose version
     * another occasion of the same name already holds is withheld as coalesced into it.
     *
     * @param evidence the occasion's retained evidence completed with what the version adds, or an
     *     {@link ItemEventInput#unretained} statement when the source cannot complete it; it never introduces evidence
     *     the occasion did not retain
     * @return whether the occasion now holds the version
     */
    public boolean pin(long workspaceId, long itemId, Unresolved event, String itemVersion, @Nullable String evidence) {
        if (evidence != null && !ItemEventInput.fits(evidence))
            throw new IllegalArgumentException("Invalid item event evidence");
        if (repository.existsByWorkspaceIdAndItemIdAndNameAndItemVersion(
                workspaceId, itemId, event.name().value(), itemVersion)) {
            decide(workspaceId, event.id(), event.state(), ItemEventState.WITHHELD, ReviewRequestReason.COALESCED);
            observeSource(workspaceId, itemId, event.name());
            return false;
        }
        if (repository.pin(workspaceId, event.id(), itemVersion, evidence) != 1) return false;
        publisher.publishEvent(new ItemEventRecorded(workspaceId, event.id(), itemId));
        return true;
    }

    /**
     * The evidence of exactly this pinned occasion of this item. Its record is readable only before its expiry; any
     * other occasion states why it has none, never with a current record in its place.
     */
    @Transactional(readOnly = true)
    public RetainedEvidence evidence(long workspaceId, long itemId, UUID eventId) {
        return repository
                .findEvidence(workspaceId, itemId, eventId)
                .map(row -> row.getItemVersion() == null
                        ? RetainedEvidence.missing(RetainedEvidence.UNKNOWN)
                        : row.getRetained() ? retained(row) : RetainedEvidence.missing(RetainedEvidence.NOT_RETAINED))
                .orElseGet(() -> RetainedEvidence.missing(RetainedEvidence.UNKNOWN));
    }

    private RetainedEvidence retained(ItemEventRepository.EvidenceRow row) {
        var unretained = row.getUnretained();
        if (unretained != null) return RetainedEvidence.missing(unretained);
        var expiresAt = row.getEvidenceExpiresAt();
        var record = row.getEvidence();
        if (record == null || expiresAt == null || !expiresAt.isAfter(clock.instant()))
            return RetainedEvidence.missing(ItemEventInput.EXPIRED);
        return new RetainedEvidence(record, null, expiresAt);
    }

    /**
     * The evidence of one occasion: its record with the instant it may no longer be read, or the reason it has none.
     *
     * @param unretained {@link #NOT_RETAINED} for an occasion recorded without evidence, {@link #UNKNOWN} for no such
     *     pinned occasion, or the {@link ItemEventInput#unretained} reason that core or its source stated
     */
    public record RetainedEvidence(
            @Nullable String record,
            @Nullable String unretained,
            @Nullable Instant expiresAt) {
        public static final String NOT_RETAINED = "NOT_RETAINED";
        public static final String UNKNOWN = "UNKNOWN";

        public RetainedEvidence {
            if ((record == null) == (unretained == null) || (record == null) != (expiresAt == null))
                throw new IllegalArgumentException("Invalid retained evidence");
        }

        static RetainedEvidence missing(String reason) {
            return new RetainedEvidence(null, reason, null);
        }
    }

    /** States that every occasion on an item no longer in the workspace's source keeps no record. */
    public void forgetEvidence(long workspaceId, long itemId) {
        repository.forgetItemEvidence(workspaceId, itemId, ItemEventInput.REMOVED);
    }

    private void observeSource(long workspaceId, long itemId, ItemEventName name) {
        if (name.value().startsWith("scm.")) publisher.publishEvent(new ItemSourceObserved(workspaceId, itemId));
    }

    public boolean decide(
            long workspaceId,
            UUID eventId,
            ItemEventState from,
            ItemEventState to,
            @Nullable ReviewRequestReason reason) {
        if (from.isTerminal()) return false;
        if ((to == ItemEventState.WAITING || to == ItemEventState.WITHHELD || to == ItemEventState.EXPIRED)
                && reason == null) throw new IllegalArgumentException("Event decision requires a reason");
        Instant now = clock.instant();
        return repository.decide(
                        workspaceId,
                        eventId,
                        new ItemEventRepository.Decision(
                                from,
                                to,
                                reason,
                                now,
                                to == ItemEventState.WAITING ? now.plus(properties.pendingRetryAfter()) : null))
                == 1;
    }
}
