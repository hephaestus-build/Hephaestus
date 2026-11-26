package de.tum.cit.aet.hephaestus.core.authority.spi;

import de.tum.cit.aet.hephaestus.core.authority.AuthorityBoundary;
import de.tum.cit.aet.hephaestus.core.authority.AuthorityRefusal;
import de.tum.cit.aet.hephaestus.core.authority.AuthorizationStamp;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.LongFunction;

/**
 * The current authorization generations. Only an authority narrowing raises one (ADR 0134 decision 2). The workspace
 * module owns the workspace's generation, and the account owns the person's.
 */
public interface AuthorityGenerations {

    /** The workspace's authorization generation; empty when the workspace no longer exists. */
    OptionalLong workspace(long workspaceId);

    /**
     * The workspace's authorization generation, held until the caller's transaction ends, so no narrowing commits while
     * that transaction admits work under it. Requires a transaction.
     */
    OptionalLong workspaceHeld(long workspaceId);

    /**
     * The person's authorization generation, which a narrowing of their AI choice, instance role or active status raises;
     * empty when the account no longer exists.
     */
    OptionalLong account(long accountId);

    /** The person's authorization generation, held as {@link #workspaceHeld} holds the workspace's. */
    OptionalLong accountHeld(long accountId);

    /**
     * Reads the stamp for work requested now, then runs {@code access}, the caller's own access check, in the same
     * transaction. A narrowing that commits after the read makes the stamp stale, so the work is refused at its next
     * boundary, also when {@code access} still saw the old authority.
     *
     * @param people the accounts whose own choices the work depends on, such as a review's developers; empty for work
     *     that depends on no person's own choice
     * @return the authority that {@code access} granted; empty when it refused
     */
    default Optional<CheckedAuthority> authorize(long workspaceId, Collection<Long> people, BooleanSupplier access) {
        var accounts = new HashMap<Long, Long>();
        for (long person : people)
            accounts.put(
                    person,
                    account(person)
                            .orElseThrow(() -> new IllegalStateException("Work depends on a person who exists")));
        var stamp = new AuthorizationStamp(
                workspace(workspaceId)
                        .orElseThrow(() -> new IllegalStateException("Work belongs to a workspace that exists")),
                accounts);
        return access.getAsBoolean() ? Optional.of(new CheckedAuthority(workspaceId, stamp)) : Optional.empty();
    }

    /**
     * The authority of work that is requested now with no access check of its own in this transaction, such as a
     * review that an event selected. A caller that checks access passes its check to {@link #authorize} instead.
     */
    default CheckedAuthority requestedNow(long workspaceId, Collection<Long> people) {
        return authorize(workspaceId, people, () -> true).orElseThrow();
    }

    /** The refusal for work that carries {@code stamp} at {@code boundary}; empty while its authority holds. */
    default Optional<AuthorityRefusal> check(AuthorizationStamp stamp, long workspaceId, AuthorityBoundary boundary) {
        return refusal(stamp, workspaceId, this::workspace, this::account, boundary);
    }

    /**
     * {@link #check}, holding each generation it reads until the caller's transaction ends: a narrowing then either
     * committed before and refuses, or waits until the caller's work was admitted. Requires a transaction.
     */
    default Optional<AuthorityRefusal> checkHeld(
            AuthorizationStamp stamp, long workspaceId, AuthorityBoundary boundary) {
        return refusal(stamp, workspaceId, this::workspaceHeld, this::accountHeld, boundary);
    }

    /**
     * Reads the people's generations in account id order, then the workspace's. Holding them takes their locks in the
     * order that an account absorption takes them: its two accounts in id order, then its memberships' workspace.
     */
    private static Optional<AuthorityRefusal> refusal(
            AuthorizationStamp stamp,
            long workspaceId,
            LongFunction<OptionalLong> workspace,
            LongFunction<OptionalLong> accounts,
            AuthorityBoundary boundary) {
        boolean personNarrowed = false;
        for (Map.Entry<Long, Long> person : new TreeMap<>(stamp.accounts()).entrySet()) {
            OptionalLong current = accounts.apply(person.getKey());
            personNarrowed |= current.isEmpty() || current.getAsLong() != person.getValue();
        }
        OptionalLong current = workspace.apply(workspaceId);
        if (current.isEmpty() || current.getAsLong() != stamp.workspace())
            return Optional.of(new AuthorityRefusal(boundary, AuthorityRefusal.Reason.WORKSPACE_AUTHORITY_NARROWED));
        if (personNarrowed)
            return Optional.of(new AuthorityRefusal(boundary, AuthorityRefusal.Reason.ACCOUNT_AUTHORITY_NARROWED));
        return Optional.empty();
    }
}
