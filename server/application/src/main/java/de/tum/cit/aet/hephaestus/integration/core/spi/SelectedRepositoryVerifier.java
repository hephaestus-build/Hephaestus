package de.tum.cit.aet.hephaestus.integration.core.spi;

import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryIdentityLookup.RepositoryIdentity;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Proves which repositories a new workspace selected, with the credential it is being created with, before the
 * workspace exists. The identities come from the provider, never from the client's list.
 */
public interface SelectedRepositoryVerifier {
    IntegrationKind kind();

    /** Resolves the account or group with the supplied credential before its workspace exists. */
    long resolveAccountId(String token, @Nullable String serverUrl, String accountLogin);

    /**
     * The provider's identity of every repository in {@code nativeIds}, in the given order. Each must be readable with
     * {@code token} and lie inside {@code accountLogin}. External lookups only, with no local write. One refused
     * repository refuses the whole selection with a 4xx; a provider that cannot answer refuses it with 502.
     */
    List<RepositoryIdentity> verify(
            String token, @Nullable String serverUrl, String accountLogin, Collection<Long> nativeIds);
}
