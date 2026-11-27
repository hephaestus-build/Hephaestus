package de.tum.cit.aet.hephaestus.core.auth.spi;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public interface AccountDirectory {
    /** It may return this transaction's cached account; after a lock or fence, read consent fresh. */
    Optional<AccountView> find(long accountId);

    Map<Long, AccountView> findAll(Collection<Long> accountIds);

    /** The active instance admins, who can receive a person's support share. */
    List<AccountView> activeInstanceAdmins();

    /**
     * The account's status and its own choices as they are committed, read past any account that the caller's
     * transaction loaded earlier. Use it under a lock that holds the row, so the values hold until that transaction
     * ends.
     */
    Optional<Consent> committedConsent(long accountId);

    record Consent(Account.Status status, @Nullable AiChoice aiChoice, boolean feedbackDeliveryEnabled) {}

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
