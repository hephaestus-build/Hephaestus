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
 * source.
 *
 * <p>Inserts use {@code ON CONFLICT DO NOTHING}: a concurrent duplicate waits for the first admission and then changes
 * nothing, where a JPA insert would abort the caller's transaction. Lock order: the source, then the authority guards
 * of {@link MentorAuthorization}, as the wire proof takes them. The source is taken {@code NOWAIT}: a held lock fails
 * the whole receive transaction, and the provider's retry admits the event again.
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

    /** {@code producer} is pinned on a new source; a legacy one is not the mentor's, so it writes nothing. */
    Admission admit(IngressEnvelope envelope, Producer producer) {
        if (envelope.eventKey().bindingId() != envelope.binding().connectionId()) {
            throw new IllegalArgumentException("The event key names another binding than the envelope.");
        }
        UUID eventId = eventId(envelope.eventKey());
        Long author = envelope.actor().linkedAccountId();
        // An unlinked actor, a bot and the app itself have no account: the provider event row keeps the disposition.
        if (author == null) return new Admission.Admitted(eventId, null);
        // Only a new question is admitted yet: an edit, a delete or a stop writes nothing.
        if (envelope.kind() != IngressKind.MESSAGE) return new Admission.Admitted(eventId, null);
        long binding = envelope.binding().connectionId();
        String container = envelope.audience().containerId();
        String message = envelope.message().nativeMessageId();
        Long stored = rows.owner(binding, container, message);
        if (stored == null && producer == Producer.LEGACY) return new Admission.Admitted(eventId, null);
        // A message that another account owns is not this author's question.
        if (stored != null && stored.longValue() != author) return new Admission.Admitted(eventId, null);
        if (!fence.holdForAccountWrite(author)) return new Admission.Admitted(eventId, null);
        owners.ofJob(author);
        if (stored == null) {
            var _ = rows.insertSource(new MentorSourceRepository.NewSource(
                    UUID.randomUUID(),
                    author,
                    binding,
                    envelope.binding().workspaceId(),
                    container,
                    message,
                    producer.name(),
                    envelope.message().revision()));
        }
        var locked = rows.lockSource(author, binding, container, message);
        if (locked.isEmpty() || Producer.valueOf(locked.get().getProducer()) == Producer.LEGACY) {
            return new Admission.Admitted(eventId, null);
        }
        UUID source = locked.get().getId();
        @Nullable UUID existing = locked.get().getRequest();
        // A new request captures authority only after its source is locked, in the wire proof's order.
        Change change = existing != null
                ? Change.recorded(Reason.ALREADY_OPENED, existing)
                : stamp(envelope, author)
                        .map(stamp -> new Change(Disposition.OPENED, null, UUID.randomUUID(), stamp))
                        .orElseGet(() -> Change.recorded(Reason.UNAUTHORIZED, null));
        int inserted = rows.insertEvent(new MentorSourceRepository.NewEvent(
                eventId,
                author,
                binding,
                envelope.eventKey().nativeEventId(),
                envelope.kind().name(),
                source,
                // The event dedupes the delivery before its request exists; it is linked once the request is open.
                change.disposition() == Disposition.OPENED ? null : change.requestId(),
                change.disposition().name(),
                change.reason() == null ? null : change.reason().name(),
                envelope.sourceRef(),
                envelope.occurredAt(),
                envelope.receivedAt()));
        if (inserted == 0) return new Admission.Duplicate(eventId);
        UUID opened = change.requestId();
        if (change.disposition() != Disposition.OPENED || opened == null) return new Admission.Admitted(eventId, null);
        var _ = rows.openRequest(opened(opened, author, source, envelope, Objects.requireNonNull(change.stamp())));
        var _ = rows.linkRequest(eventId, author, opened);
        return new Admission.Admitted(eventId, new RequestRef(opened, 1));
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
