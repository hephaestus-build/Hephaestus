package de.tum.cit.aet.hephaestus.mentor.runtime;

import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.mentor.runtime.MentorAttempt.WorkClass;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leases the mentor's attempts. Every method binds one account and takes the account's request before its attempt, in
 * separate statements. Nothing here calls a model or a provider.
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

    /**
     * Leases up to {@code limit} due attempts of the class for one account. A claim never waits: a held owner row, request
     * or attempt is skipped, and the next pass takes it.
     */
    @Transactional
    public List<AttemptLease> claim(long account, WorkClass workClass, int limit, Duration lease) {
        if (limit <= 0 || !fence.holdForAccountWrite(account)) return List.of();
        owners.ofJob(account);
        if (attempts.lockOwnerOrSkip(account).isEmpty()) return List.of();
        var live = attempts.liveLeases(account);
        long room = Math.min(limit, OWNER_LEASES - live.getTotal());
        if (workClass == WorkClass.BACKGROUND) room = Math.min(room, OWNER_BACKGROUND - live.getBackground());
        var leases = new ArrayList<AttemptLease>();
        if (room <= 0) return leases;
        // A few more candidates than room, so a skipped row does not leave a lease unclaimed this pass.
        for (var due : attempts.due(account, workClass.name(), MentorAttempt.MAX_CLAIMS, (int) room * 2)) {
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
        return leases;
    }

    private static double seconds(Duration duration) {
        return duration.toMillis() / 1000.0;
    }
}
