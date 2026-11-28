package de.tum.cit.aet.hephaestus.mentor.runtime;

import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.mentor.runtime.MentorAttempt.WorkClass;
import de.tum.cit.aet.hephaestus.mentor.runtime.MentorAttemptRepository.LiveRequest;
import de.tum.cit.aet.hephaestus.mentor.spi.Audience;
import de.tum.cit.aet.hephaestus.mentor.spi.AuthorityScope;
import de.tum.cit.aet.hephaestus.mentor.spi.MentorAuthorization;
import de.tum.cit.aet.hephaestus.mentor.spi.MentorSourceEvidence;
import de.tum.cit.aet.hephaestus.mentor.spi.ProviderObservation;
import de.tum.cit.aet.hephaestus.mentor.spi.ScopeStamp;
import de.tum.cit.aet.hephaestus.mentor.spi.Transport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leases the mentor's attempts and proves a lease before each model call. Every method binds one account and takes the
 * account's request before its attempt, in separate statements. Nothing here calls a model or a provider.
 *
 * <p>The limits that matter hold across replicas: a claim holds the account's owner row while it counts the account's
 * running leases. One lease is always left for a question, because background work holds at most one.
 */
@Component
@ConditionalOnServerRole
@OwnerScope.StoredOwnerOperation
@RequiredArgsConstructor
public class MentorAttempts {
    /** Running leases per account, across replicas. */
    static final int OWNER_LEASES = 2;

    /** Running background leases per account, so background work never takes the last lease. */
    static final int OWNER_BACKGROUND = 1;

    private final MentorAttemptRepository attempts;
    private final PersonDataWriteFence fence;
    private final OwnerScope owners;
    private final ObjectProvider<MentorAuthorization> authorization;
    private final ObjectProvider<MentorSourceEvidence> evidence;

    /**
     * Leases up to {@code limit} due attempts of the class for one account. A claim never waits: a held owner row, request
     * or attempt is skipped, and the next pass takes it. It also ends the account's attempts that a newer version
     * replaced, unless a lease still holds them.
     */
    @Transactional
    public List<AttemptLease> claim(long account, WorkClass workClass, int limit, Duration lease) {
        // A lease that is already over would spend one of the attempt's claims on nothing.
        if (!lease.isPositive()) throw new IllegalArgumentException("A lease must last: " + lease);
        if (limit <= 0 || !fence.holdForAccountWrite(account)) return List.of();
        owners.ofJob(account);
        if (attempts.lockOwnerOrSkip(account).isEmpty()) return List.of();
        var live = attempts.liveLeases(account);
        long room = Math.min(limit, OWNER_LEASES - live.getTotal());
        if (workClass == WorkClass.BACKGROUND) room = Math.min(room, OWNER_BACKGROUND - live.getBackground());
        var leases = new ArrayList<AttemptLease>();
        // A few more candidates than room, so a skipped row does not leave a lease unclaimed this pass.
        var candidates = room <= 0
                ? List.<MentorAttemptRepository.Due>of()
                : attempts.due(account, workClass.name(), MentorAttempt.MAX_CLAIMS, (int) room * 2);
        for (var due : candidates) {
            if (leases.size() == room) break;
            if (attempts.lockLiveRequestOrSkip(account, due.getRequest(), due.getVersion())
                    .isEmpty()) continue;
            UUID token = UUID.randomUUID();
            var leased =
                    attempts.leaseOrSkip(account, due.getAttempt(), token, seconds(lease), MentorAttempt.MAX_CLAIMS);
            if (leased.isEmpty()) continue;
            var _ = attempts.markRunning(account, due.getRequest());
            leases.add(new AttemptLease(
                    account,
                    due.getAttempt(),
                    due.getRequest(),
                    due.getVersion(),
                    workClass,
                    leased.get().getEpoch(),
                    token,
                    leased.get().getLeasedUntil()));
        }
        // After the request locks above, so the claim keeps the request before the attempt.
        var _ = attempts.endStaleOrSkip(account);
        return leases;
    }

    /** Extends a lease that still runs while its request is live at its version; false when the holder lost it. */
    @Transactional
    public boolean renew(AttemptLease lease, Duration duration) {
        if (!fence.holdForAccountWrite(lease.accountId())) return false;
        owners.ofJob(lease.accountId());
        if (attempts.shareLiveRequest(lease.accountId(), lease.requestId(), lease.requestVersion())
                .isEmpty()) return false;
        return attempts.renew(
                        lease.accountId(), lease.attemptId(), lease.leaseEpoch(), lease.leaseToken(), seconds(duration))
                == 1;
    }

    /**
     * The dispatch fence: whether the credential may still make a model call, in the caller's transaction. Before
     * evidence is read and inside each grant, it holds the request and then the attempt against a Stop, an edit, a
     * reclaim and the commit, and rechecks the request's stored authority under its guards. Only a Slack attempt can
     * pass, and it also needs an observation bound at this lease epoch that is still fresh. Database only.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean current(AttemptCredential credential) {
        long account = credential.accountId();
        owners.ofJob(account);
        var attempt = attempts.attemptOf(account, credential.attemptId());
        if (attempt.isEmpty()) return false;
        var request = attempts.shareLiveRequest(
                account, attempt.get().getRequest(), attempt.get().getVersion());
        if (request.isEmpty()) return false;
        var observed = attempts.shareHeldAttempt(credential, seconds(ProviderObservation.FRESH_FOR));
        if (observed.isEmpty()) return false;
        var audience = audience(request.get());
        boolean observedNow = switch (audience.transport()) {
            case SLACK -> observed.get();
            // No intake opens an app question yet. It refuses until the app intake (A1) brings its own checks.
            case APP -> false;
        };
        if (!observedNow) return false;
        @Nullable MentorAuthorization port = authorization.getIfAvailable();
        if (port == null || !port.isCurrent(stamp(account, request.get()), audience)) return false;
        return sourceLive(request.get());
    }

    /**
     * Intake can miss a delete that its retries never admitted, so a Slack question also needs the Slack store's own
     * record: a tombstone there refuses, and so does an unwired port. Its row is held last, after the authority guards.
     */
    private boolean sourceLive(LiveRequest request) {
        if (!"LIVE".equals(request.getSourceState())) return false;
        @Nullable MentorSourceEvidence store = evidence.getIfAvailable();
        return store != null
                && !store.deleted(
                        request.getSourceWorkspaceId(), request.getSourceContainerId(), request.getSourceMessageId());
    }

    static Audience audience(LiveRequest request) {
        return new Audience(
                Transport.valueOf(request.getAudienceTransport()),
                request.getAudienceConnectionId(),
                request.getAudienceContainerId(),
                request.getAudienceThreadRoot());
    }

    /** The stamp that intake stored with the request, rebuilt for its live recheck. */
    static ScopeStamp stamp(long account, LiveRequest request) {
        AuthorityScope authority = switch (request.getScopeAuthority()) {
            case "PERSONAL" -> new AuthorityScope.Personal();
            case "WORKSPACE" ->
                new AuthorityScope.Workspace(
                        Objects.requireNonNull(request.getScopeWorkspaceId()),
                        Objects.requireNonNull(request.getScopeWorkspaceGeneration()));
            case "SLACK" ->
                new AuthorityScope.Slack(
                        Objects.requireNonNull(request.getScopeWorkspaceId()),
                        Objects.requireNonNull(request.getScopeWorkspaceGeneration()),
                        Objects.requireNonNull(request.getScopeBindingId()),
                        Objects.requireNonNull(request.getScopeBindingGeneration()),
                        Objects.requireNonNull(request.getScopeNativeActorId()));
            default -> throw new IllegalStateException("Unknown authority " + request.getScopeAuthority());
        };
        return new ScopeStamp(
                account,
                request.getScopeAccountGeneration(),
                request.getScopeReaderEpoch(),
                request.getScopeConsentEpoch(),
                authority);
    }

    private static double seconds(Duration duration) {
        return duration.toMillis() / 1000.0;
    }
}
