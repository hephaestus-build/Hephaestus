package de.tum.cit.aet.hephaestus.core.auth.oauth;

import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProvider;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProviderRepository;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Completes a sign-in only against the login provider row version that authenticated it. Spring authenticated the
 * callback with the registration of one row version; an admin can replace or edit the row before provisioning reads
 * it. Provisioning then runs in the same transaction as a share lock on that row, so the subject is keyed only into
 * the namespace of the row that authenticated it. A mismatch is a sign-in failure: nothing is attached or created.
 */
@ConditionalOnServerRole
@Component
public class ProviderBinding {

    /** The failure code of a sign-in whose provider row changed after it authenticated. */
    static final String PROVIDER_CHANGED = "provider_changed";

    private final LoginProviderRepository providers;
    private final TransactionTemplate transactions;

    public ProviderBinding(LoginProviderRepository providers, PlatformTransactionManager transactionManager) {
        this.providers = providers;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public <T> T provision(OAuth2AuthenticationToken token, Supplier<T> provisioning) {
        String authenticated = token instanceof ProviderBoundToken bound ? bound.providerVersion() : null;
        return Objects.requireNonNull(transactions.execute(status -> {
            String current = providers
                    .findByRegistrationIdForShare(token.getAuthorizedClientRegistrationId())
                    .map(LoginProvider::registrationVersion)
                    .orElse(null);
            if (authenticated == null || !authenticated.equals(current)) {
                throw new OAuth2AuthenticationException(PROVIDER_CHANGED);
            }
            return provisioning.get();
        }));
    }
}
