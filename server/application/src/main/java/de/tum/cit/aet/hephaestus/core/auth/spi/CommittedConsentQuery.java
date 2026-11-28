package de.tum.cit.aet.hephaestus.core.auth.spi;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * An account's status and its own choices as they are committed, read past any account that the caller's transaction
 * loaded earlier. Use it under a lock that holds the row, so the values hold until that transaction ends.
 */
public interface CommittedConsentQuery {
    Optional<Consent> committedConsent(long accountId);

    record Consent(Account.Status status, @Nullable AiChoice aiChoice, boolean feedbackDeliveryEnabled) {}
}
