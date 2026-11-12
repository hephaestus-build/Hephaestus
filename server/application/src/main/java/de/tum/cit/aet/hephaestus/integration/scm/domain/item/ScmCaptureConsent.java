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
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Checks every selected contributor, independently of the practice's About role. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ScmCaptureConsent {
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

    /** The one consent decision for current and retained contributors alike; it records a copy of each it permits. */
    public void requireContributors(Collection<Contributor> contributors) {
        var distinct = new LinkedHashMap<PersonIdentity, Contributor>();
        contributors.forEach(contributor -> distinct.putIfAbsent(contributor.identity(), contributor));
        for (Contributor contributor : distinct.values()) {
            var identity = contributor.identity();
            if (suppression.isSuppressed(identity.providerId(), identity.subject(), null)) throw withheld();
            Optional<Long> accountId;
            try {
                accountId = identities.accountFor(identity);
            } catch (ResponseStatusException conflict) {
                if (conflict.getStatusCode().value() == HttpStatus.CONFLICT.value())
                    throw new ItemCaptureException(
                            ItemCaptureCode.CONSENT_WITHHELD,
                            "A contributor has withheld permission to process this work",
                            conflict);
                throw conflict;
            }
            if (accountId.isPresent()) {
                var account = accounts.find(accountId.orElseThrow()).orElseThrow(ScmCaptureConsent::withheld);
                if (suppression.isAccountSuppressed(account.id())
                        || account.aiChoice() == AiChoice.NO_AI
                        || (account.status() != Account.Status.ACTIVE && account.status() != Account.Status.UNCLAIMED))
                    throw withheld();
            }
            copies.recordIdentity(contributor.copy());
        }
    }

    private static ItemCaptureException withheld() {
        return new ItemCaptureException(
                ItemCaptureCode.CONSENT_WITHHELD, "A contributor has withheld permission to process this work");
    }
}
