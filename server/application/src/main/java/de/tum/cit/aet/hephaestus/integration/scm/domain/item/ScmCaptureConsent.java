package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Checks every selected contributor, independently of the practice's About role. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ScmCaptureConsent {
    private static final String WITHHELD = "A contributor has withheld permission to process this work";

    /** The batch open on this thread, if any. */
    private static final ThreadLocal<@Nullable Batch> BATCH = new ThreadLocal<>();

    private final PersonIdentityResolver identities;
    private final AccountDirectory accounts;
    private final PersonProcessingSuppression suppression;
    private final PersonDataCopyRecorder copies;

    /**
     * One person whose content a capture exports: the native identity privacy decides by, and the identity a copy of
     * their content is recorded under. A retained occasion stores both, so it needs no mirror row of the person.
     */
    public record Contributor(PersonIdentity identity, PersonCopyIdentity copy) {
        public static Contributor of(User user) {
            var provider = user.getProvider();
            Long providerId = provider.getId();
            if (providerId == null)
                throw new ItemCaptureException(
                        ItemCaptureCode.CAPTURE_FAILED, "Contributor provider identity is unavailable");
            String key = Long.toString(user.getNativeId());
            return new Contributor(
                    new PersonIdentity(providerId, key, null),
                    new PersonCopyIdentity(provider.getType().name(), provider.getServerUrl(), key, null));
        }
    }

    public void require(Collection<User> contributors) {
        requireContributors(contributors.stream().map(Contributor::of).toList());
    }

    /**
     * Opens a batch when {@code several} sources of the same work are captured: a contributor that consent permitted is
     * not checked again by a later source, and every source still records the copies it exports. A withheld or failed
     * check keeps nothing. A batch already open on this thread is joined. A capture of one source opens none, so every
     * review capture decides on its own.
     *
     * @return whether this call opened the batch, which {@link #settleBatch} and {@link #closeBatch} then take
     */
    static boolean openBatch(boolean several) {
        if (!several || BATCH.get() != null) return false;
        BATCH.set(new Batch());
        return true;
    }

    /**
     * Decides every contributor that the batch kept once more, after its last source composed. A withdrawal that
     * committed while the batch ran fails it here, as the check of a later source would have.
     */
    static void settleBatch(boolean opened) {
        var batch = BATCH.get();
        if (!opened || batch == null) return;
        var decider = batch.decider;
        if (decider != null) batch.permitted.forEach(decider::decide);
    }

    /** Drops the batch that {@link #openBatch} opened. */
    static void closeBatch(boolean opened) {
        if (opened) BATCH.remove();
    }

    /** The one consent decision for current and retained contributors alike; it records a copy of each it permits. */
    public void requireContributors(Collection<Contributor> contributors) {
        var distinct = new LinkedHashMap<PersonIdentity, Contributor>();
        contributors.forEach(contributor -> distinct.putIfAbsent(contributor.identity(), contributor));
        var batch = BATCH.get();
        for (Contributor contributor : distinct.values()) {
            var identity = contributor.identity();
            if (batch == null || !batch.permitted.contains(identity)) {
                decide(identity);
                if (batch != null) {
                    batch.permitted.add(identity);
                    batch.decider = this;
                }
            }
            copies.recordIdentity(contributor.copy());
        }
    }

    private void decide(PersonIdentity identity) {
        if (suppression.isSuppressed(identity.providerId(), identity.subject(), null))
            throw new ItemCaptureException(ItemCaptureCode.CONSENT_WITHHELD, WITHHELD);
        Optional<Long> accountId;
        try {
            accountId = identities.accountFor(identity);
        } catch (ResponseStatusException conflict) {
            if (conflict.getStatusCode().value() == HttpStatus.CONFLICT.value())
                throw new ItemCaptureException(ItemCaptureCode.CONSENT_WITHHELD, WITHHELD, conflict);
            throw conflict;
        }
        if (accountId.isPresent()) {
            var account = accounts.find(accountId.orElseThrow()).orElse(null);
            if (account == null
                    || suppression.isAccountSuppressed(account.id())
                    || account.aiChoice() == AiChoice.NO_AI
                    || (account.status() != Account.Status.ACTIVE && account.status() != Account.Status.UNCLAIMED))
                throw new ItemCaptureException(ItemCaptureCode.CONSENT_WITHHELD, WITHHELD);
        }
    }

    /** The contributors that consent permitted in one batch, and the consent that decided them. */
    private static final class Batch {
        private final Set<PersonIdentity> permitted = new LinkedHashSet<>();
        private @Nullable ScmCaptureConsent decider;
    }
}
