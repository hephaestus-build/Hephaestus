package de.tum.cit.aet.hephaestus.mentor.runtime;

import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.mentor.runtime.MentorEvent.Disposition;
import de.tum.cit.aet.hephaestus.mentor.runtime.MentorEvent.Reason;
import de.tum.cit.aet.hephaestus.mentor.runtime.MentorSourceRepository.OpenedRequest;
import de.tum.cit.aet.hephaestus.mentor.spi.Admission;
import de.tum.cit.aet.hephaestus.mentor.spi.Audience;
import de.tum.cit.aet.hephaestus.mentor.spi.AuthorityScope;
import de.tum.cit.aet.hephaestus.mentor.spi.Binding;
import de.tum.cit.aet.hephaestus.mentor.spi.IngressEnvelope;
import de.tum.cit.aet.hephaestus.mentor.spi.IngressKind;
import de.tum.cit.aet.hephaestus.mentor.spi.MentorAuthorization;
import de.tum.cit.aet.hephaestus.mentor.spi.MentorIngress;
import de.tum.cit.aet.hephaestus.mentor.spi.Producer;
import de.tum.cit.aet.hephaestus.mentor.spi.ProviderEventKey;
import de.tum.cit.aet.hephaestus.mentor.spi.RequestRef;
import de.tum.cit.aet.hephaestus.mentor.spi.ScopeStamp;
import de.tum.cit.aet.hephaestus.mentor.spi.Transport;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admits verified provider events in the transport's receive transaction: database only, and the only writer of the
 * mentor's sources, events and requests. A message's owner is the account of its verified author, pinned on its
 * source; an edit or a delete binds that stored owner.
 *
 * <p>Inserts use {@code ON CONFLICT DO NOTHING}: a concurrent duplicate waits for the first admission and then changes
 * nothing, where a JPA insert would abort the caller's transaction. Lock order: the source, then its request, then the
 * authority guards of {@link MentorAuthorization}, as the wire proof takes them. The source and the request are taken
 * {@code NOWAIT}: a held lock fails the whole receive transaction, and the provider's retry admits the event again.
 */
@Component
@ConditionalOnServerRole
@OwnerScope.StoredOwnerOperation
@RequiredArgsConstructor
public class MentorIntake implements MentorIngress {
    static final Duration ANSWER_DEADLINE = Duration.ofHours(1);

    private final MentorSourceRepository rows;
    private final PersonDataWriteFence fence;
    private final OwnerScope owners;
    private final ObjectProvider<MentorAuthorization> authorization;

    /**
     * Every route stays legacy, so intake stays dark. No route cuts over before the transport fence and the live ports
     * are wired (R5), and never before the release that registers the mentor stores in the person-data inventory: a
     * mentor row written earlier would be one that erasure does not reach.
     */
    @Override
    public Producer route(Binding binding, Audience audience) {
        return Producer.LEGACY;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Admission admit(IngressEnvelope envelope) {
        return admit(envelope, route(envelope.binding(), envelope.audience()));
    }

    /**
     * {@code producer} is pinned on a new source, and only a message's first event asks for it: a new message, or a
     * delete that arrives before it. A mutation of a source the mentor pinned is admitted whatever the route answers
     * now, so a rollback keeps fencing the requests it opened.
     */
    Admission admit(IngressEnvelope envelope, Producer producer) {
        if (envelope.eventKey().bindingId() != envelope.binding().connectionId()) {
            throw new IllegalArgumentException("The event key names another binding than the envelope.");
        }
        // Only a seal writes a sealed review's event (MentorReviewSealedIntake). A provider envelope never carries one.
        if (envelope.kind() == IngressKind.REVIEW_SEALED) {
            throw new IllegalArgumentException("A provider event cannot be a sealed review.");
        }
        UUID eventId = eventId(envelope.eventKey());
        Long author = envelope.actor().linkedAccountId();
        // An unlinked actor, a bot and the app itself have no account: the provider event row keeps the disposition.
        if (author == null) return new Admission.Admitted(eventId, null);
        long binding = envelope.binding().connectionId();
        String container = envelope.audience().containerId();
        String message = envelope.message().nativeMessageId();
        Long stored = rows.owner(binding, container, message);
        // A new question starts a source, and so does a delete that arrives first: its tombstone keeps a late copy of
        // the create from opening the question. An edit or a Stop of a message the mentor does not hold writes nothing.
        boolean deleted = envelope.kind() == IngressKind.MESSAGE_DELETED;
        boolean starts = producer == Producer.MENTOR && (envelope.kind() == IngressKind.MESSAGE || deleted);
        if (stored == null && !starts) return new Admission.Admitted(eventId, null);
        long owner = stored == null ? author : stored;
        if (!fence.holdForAccountWrite(owner)) return new Admission.Admitted(eventId, null);
        owners.ofJob(owner);
        if (stored == null) {
            var _ = rows.insertSource(new MentorSourceRepository.NewSource(
                    UUID.randomUUID(),
                    owner,
                    binding,
                    envelope.binding().workspaceId(),
                    container,
                    message,
                    producer.name(),
                    envelope.message().revision(),
                    (deleted ? MentorSource.State.DELETED : MentorSource.State.LIVE).name()));
        }
        var locked = rows.lockSource(owner, binding, container, message);
        if (locked.isEmpty() || Producer.valueOf(locked.get().getProducer()) == Producer.LEGACY) {
            return new Admission.Admitted(eventId, null);
        }
        Source source = Source.of(locked.get());
        @Nullable
        Request request = rows.lockRequest(source.id(), owner).map(Request::of).orElse(null);
        @Nullable UUID requestId = request == null ? null : request.id();
        Change change = owner == author
                ? decide(envelope, source, request, owner)
                : Change.recorded(Reason.AUTHOR_MISMATCH, requestId);
        int inserted = rows.insertEvent(new MentorSourceRepository.NewEvent(
                eventId,
                owner,
                binding,
                envelope.eventKey().nativeEventId(),
                envelope.kind().name(),
                source.id(),
                // The event dedupes the delivery before its request exists; it is linked once the request is open.
                change.disposition() == Disposition.OPENED ? null : change.requestId(),
                change.disposition().name(),
                change.reason() == null ? null : change.reason().name(),
                envelope.sourceRef(),
                envelope.occurredAt(),
                envelope.receivedAt()));
        if (inserted == 0) return new Admission.Duplicate(eventId);
        return new Admission.Admitted(eventId, apply(envelope, eventId, source, request, change, owner));
    }

    /** Runs after the source and its request are locked; it captures authority only then, in the wire proof's order. */
    private Change decide(IngressEnvelope envelope, Source source, @Nullable Request request, long account) {
        if (source.state() == MentorSource.State.DELETED) {
            return Change.recorded(Reason.SOURCE_DELETED, request == null ? null : request.id());
        }
        boolean settled = request != null && request.state().settled();
        return switch (envelope.kind()) {
            case MESSAGE -> {
                if (request != null) yield Change.recorded(Reason.ALREADY_OPENED, request.id());
                yield stamp(envelope, account)
                        .map(stamp -> new Change(Disposition.OPENED, null, UUID.randomUUID(), stamp))
                        .orElseGet(() -> Change.recorded(Reason.UNAUTHORIZED, null));
            }
            case MESSAGE_EDITED -> {
                if (!newer(envelope.message().revision(), source.revision())) {
                    yield Change.recorded(Reason.STALE_REVISION, request == null ? null : request.id());
                }
                if (request == null) yield Change.recorded(Reason.NO_REQUEST, null);
                if (settled) yield Change.recorded(Reason.REQUEST_SETTLED, request.id());
                yield stamp(envelope, account).isPresent()
                        ? new Change(Disposition.REVISED, null, request.id(), null)
                        : Change.recorded(Reason.UNAUTHORIZED, request.id());
            }
            case MESSAGE_DELETED -> {
                if (request == null) yield Change.recorded(Reason.NO_REQUEST, null);
                if (settled) yield Change.recorded(Reason.REQUEST_SETTLED, request.id());
                yield new Change(Disposition.FENCED, null, request.id(), null);
            }
            case CONTROL_STOP -> Change.recorded(Reason.STOP_NOT_HANDLED, request == null ? null : request.id());
            case REVIEW_SEALED -> throw new IllegalStateException("admit refuses a sealed review before any write");
        };
    }

    /** A delete always leaves the tombstone; a newer edit raises the stored revision even when the request is settled. */
    private @Nullable RequestRef apply(
            IngressEnvelope envelope,
            UUID eventId,
            Source source,
            @Nullable Request request,
            Change change,
            long account) {
        if (change.reason() == Reason.AUTHOR_MISMATCH || source.state() == MentorSource.State.DELETED) return null;
        if (envelope.kind() == IngressKind.MESSAGE_DELETED) {
            var _ = rows.markDeleted(source.id(), account);
        } else if (envelope.kind() == IngressKind.MESSAGE_EDITED
                && newer(envelope.message().revision(), source.revision())) {
            var _ = rows.raiseRevision(source.id(), account, envelope.message().revision());
        }
        UUID id = change.requestId();
        if (id == null) return null;
        return switch (change.disposition()) {
            case OPENED -> {
                var _ = rows.openRequest(
                        opened(id, account, source.id(), envelope, Objects.requireNonNull(change.stamp())));
                var _ = rows.linkRequest(eventId, account, id);
                yield new RequestRef(id, 1);
            }
            case REVISED -> {
                var _ = rows.revise(id, account);
                // The request is locked, so its next version is the locked one plus one.
                yield new RequestRef(id, Objects.requireNonNull(request).version() + 1);
            }
            case FENCED -> {
                var _ = rows.stop(id, account);
                yield new RequestRef(id, Objects.requireNonNull(request).version());
            }
            case RECORDED -> null;
            case PENDING -> throw new IllegalStateException("Only a sealed review's event waits, and intake writes none");
        };
    }

    /** An absent authorization port, another transport or a stamp for another account opens no request. */
    private Optional<ScopeStamp> stamp(IngressEnvelope envelope, long account) {
        @Nullable MentorAuthorization port = authorization.getIfAvailable();
        if (port == null || envelope.audience().transport() != Transport.SLACK) return Optional.empty();
        return port.captureSlack(envelope.actor(), envelope.binding(), envelope.audience())
                .filter(stamp -> stamp.accountId() == account);
    }

    private static OpenedRequest opened(
            UUID id, long account, UUID source, IngressEnvelope envelope, ScopeStamp scope) {
        Audience audience = envelope.audience();
        Authority authority = switch (scope.authority()) {
            case AuthorityScope.Personal() -> new Authority("PERSONAL", null, null, null, null, null);
            case AuthorityScope.Workspace(long workspace, long generation) ->
                new Authority("WORKSPACE", workspace, generation, null, null, null);
            case AuthorityScope.Slack slack ->
                new Authority(
                        "SLACK",
                        slack.workspaceId(),
                        slack.workspaceGeneration(),
                        slack.bindingId(),
                        slack.bindingGeneration(),
                        slack.nativeActorId());
        };
        return new OpenedRequest(
                id,
                account,
                source,
                audience.transport().name(),
                audience.connectionId(),
                audience.containerId(),
                audience.threadRoot(),
                scope.accountGeneration(),
                scope.readerEpoch(),
                scope.consentEpoch(),
                authority.kind(),
                authority.workspace(),
                authority.workspaceGeneration(),
                authority.binding(),
                authority.bindingGeneration(),
                authority.actor(),
                envelope.receivedAt(),
                envelope.receivedAt().plus(ANSWER_DEADLINE));
    }

    /** A provider revision is applied only when it is newer than the stored one. Slack revisions are decimal stamps. */
    private static boolean newer(String revision, String stored) {
        return new BigDecimal(revision).compareTo(new BigDecimal(stored)) > 0;
    }

    static UUID eventId(ProviderEventKey key) {
        return UUID.nameUUIDFromBytes(
                ("mentor-event:" + key.bindingId() + ":" + key.nativeEventId()).getBytes(StandardCharsets.UTF_8));
    }

    /** The scope columns of one {@code AuthorityScope}; the columns of another kind are NULL. */
    private record Authority(
            String kind,
            @Nullable Long workspace,
            @Nullable Long workspaceGeneration,
            @Nullable Long binding,
            @Nullable Long bindingGeneration,
            @Nullable String actor) {}

    private record Source(UUID id, MentorSource.State state, String revision) {
        static Source of(MentorSourceRepository.LockedSource row) {
            return new Source(row.getId(), MentorSource.State.valueOf(row.getState()), row.getRevision());
        }
    }

    private record Request(UUID id, MentorRequest.State state, int version) {
        static Request of(MentorSourceRepository.LockedRequest row) {
            return new Request(row.getId(), MentorRequest.State.valueOf(row.getState()), row.getVersion());
        }
    }

    private record Change(
            Disposition disposition,
            @Nullable Reason reason,
            @Nullable UUID requestId,
            @Nullable ScopeStamp stamp) {
        static Change recorded(Reason reason, @Nullable UUID request) {
            return new Change(Disposition.RECORDED, reason, request, null);
        }
    }
}
