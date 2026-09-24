package de.tum.cit.aet.hephaestus.integration.identity.connect;

import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration-side implementation of the {@link GitProviderRegistry} auth SPI: upserts the
 * {@code git_provider} row for a login provider so {@code core.auth}'s account provisioning can key
 * the {@code IdentityLink} by {@code (git_provider_id, subject)} without importing the
 * {@link IdentityProvider} entity. {@code core.auth} passes the provider's {@code (type, baseUrl)} (read
 * from its own {@code login_provider} store); this side owns the {@code IdentityProvider} row and
 * canonicalizes {@code baseUrl} to the server-url origin.
 */
@Component
public class RegistrationToGitProviderResolver implements GitProviderRegistry {

    private static final String UNKNOWN = "UNKNOWN";

    private final IdentityProviderRepository gitProviderRepository;
    private final UserRepository userRepository;

    public RegistrationToGitProviderResolver(
            IdentityProviderRepository gitProviderRepository, UserRepository userRepository) {
        this.gitProviderRepository = gitProviderRepository;
        this.userRepository = userRepository;
    }

    /**
     * Get-or-create the {@code identity_provider} row, committing in its OWN transaction
     * ({@link Propagation#REQUIRES_NEW}). This is required for correctness, not just isolation: account
     * provisioning inserts the {@code identity_link} (FK {@code sfk_identity_link_provider}) inside
     * {@code AccountJitCreator}'s {@code REQUIRES_NEW} transaction, which under READ_COMMITTED cannot see
     * an uncommitted {@code identity_provider} row. The first login on a not-yet-seen instance (e.g. a
     * self-hosted gitlab.lrz.de) would otherwise create the row in the outer login transaction and then
     * fail the FK from the inner JIT transaction. The provider row is idempotent reference data (an SCM
     * instance registration, reused across logins — exactly like the env-seeded github.com / gitlab.com
     * rows), so committing it independently of the login outcome is correct.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long resolveProviderId(String providerTypeName, String baseUrl) {
        IdentityProviderType type = IdentityProviderType.valueOf(providerTypeName);
        String origin = originOf(baseUrl);
        return Objects.requireNonNull(gitProviderRepository
                .findByTypeAndServerUrl(type, origin)
                .orElseGet(() -> gitProviderRepository.save(new IdentityProvider(type, origin)))
                .getId());
    }

    @Override
    @Transactional(readOnly = true)
    public String providerTypeName(@Nullable Long gitProviderId) {
        if (gitProviderId == null) {
            return UNKNOWN;
        }
        return gitProviderRepository
                .findById(gitProviderId)
                .map(p -> p.getType().name())
                .orElse(UNKNOWN);
    }

    @Override
    @Transactional(readOnly = true)
    public @Nullable String providerServerUrl(@Nullable Long gitProviderId) {
        if (gitProviderId == null) {
            return null;
        }
        return gitProviderRepository
                .findById(gitProviderId)
                .map(IdentityProvider::getServerUrl)
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> findActorId(long gitProviderId, String subject) {
        final long nativeId;
        try {
            nativeId = Long.parseLong(subject);
        } catch (NumberFormatException notAGitSubject) {
            return Optional.empty();
        }
        return userRepository
                .findByNativeIdAndProviderId(nativeId, gitProviderId)
                .map(User::getId);
    }

    /**
     * The scheme + host (+ explicit non-default port) of the base URL — the canonical server URL used
     * to key the {@code git_provider} row (e.g. {@code https://github.com}, {@code https://gitlab.lrz.de}).
     */
    private static String originOf(String baseUrl) {
        try {
            URI uri = new URI(baseUrl);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalStateException("login provider baseUrl has no scheme/host: " + baseUrl);
            }
            String origin = uri.getScheme() + "://" + uri.getHost();
            return uri.getPort() == -1 ? origin : origin + ":" + uri.getPort();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("malformed login provider baseUrl: " + baseUrl, e);
        }
    }
}
