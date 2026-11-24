package de.tum.cit.aet.hephaestus.core.auth.spi;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public interface AccountDirectory {
    Optional<AccountView> find(long accountId);

    /**
     * Locks the account row until the caller's transaction ends, with the lock that the account's erasure takes, and
     * says whether the account is active. Two callers for one account wait for each other.
     */
    boolean lockWhileActive(long accountId);

    Map<Long, AccountView> findAll(Collection<Long> accountIds);

    /** Locks an account eligible for retention in the caller's transaction. */
    boolean holdForRetention(long accountId);

    void setFeedbackDeliveryEnabled(long accountId, boolean enabled);

    void setAiChoice(long accountId, @Nullable AiChoice choice);

    /** The time zone of a developer who chose none, or whose choice this runtime does not know. */
    ZoneId DEFAULT_TIME_ZONE = ZoneOffset.UTC;

    /** The time zone the account chose, when this runtime knows it. */
    Optional<ZoneId> timeZone(long accountId);

    /** Sets the account's IANA time zone id; {@code null} clears it. */
    void setTimeZone(long accountId, @Nullable String timeZone);

    record AccountView(
            long id,
            Account.Status status,
            String displayName,
            @Nullable AiChoice aiChoice,
            @Nullable Instant aiChoiceAt,
            boolean feedbackDeliveryEnabled) {

        /** Whether the account is in its erasure cooldown or already erased, so nothing may name it any more. */
        public boolean erased() {
            return status == Account.Status.DELETING || status == Account.Status.DELETED;
        }
    }
}
