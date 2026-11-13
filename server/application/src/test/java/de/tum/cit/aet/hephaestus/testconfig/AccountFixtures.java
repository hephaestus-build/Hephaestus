package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import java.time.Instant;

/** Accounts in a given status, reached through the legal moves of {@link Account.Status}. */
public final class AccountFixtures {
    private AccountFixtures() {}

    public static Account withStatus(String displayName, Account.Status status) {
        var account = status == Account.Status.UNCLAIMED ? Account.unclaimed(displayName) : new Account(displayName);
        moveTo(account, status);
        return account;
    }

    /** Moves a new active or unclaimed account on to {@code status}. */
    public static void moveTo(Account account, Account.Status status) {
        if (account.getStatus() == status) {
            return;
        }
        switch (status) {
            case UNCLAIMED -> throw new IllegalArgumentException("Create an unclaimed account with Account.unclaimed");
            case ACTIVE -> account.claim();
            case BLOCKED -> {
                if (account.getStatus() == Account.Status.UNCLAIMED) account.claim();
                account.block();
            }
            case DELETING -> account.startDeletion(Instant.EPOCH);
            case DELETED -> {
                account.startDeletion(Instant.EPOCH);
                account.markDeleted();
            }
        }
    }
}
