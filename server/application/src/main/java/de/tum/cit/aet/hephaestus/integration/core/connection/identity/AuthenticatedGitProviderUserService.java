package de.tum.cit.aet.hephaestus.integration.core.connection.identity;

import de.tum.cit.aet.hephaestus.core.LoggingUtils;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery.IdentityLinkView;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolves linked SCM identities by immutable provider subject. Missing actors are provisioned
 * only when their saved signup metadata does not conflict with an existing actor.
 */
@Service
public class AuthenticatedGitProviderUserService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticatedGitProviderUserService.class);

    private final UserRepository userRepository;
    private final IdentityProviderRepository gitProviderRepository;
    private final AccountIdentityQuery accountIdentityQuery;

    public AuthenticatedGitProviderUserService(
            UserRepository userRepository,
            IdentityProviderRepository gitProviderRepository,
            AccountIdentityQuery accountIdentityQuery) {
        this.userRepository = userRepository;
        this.gitProviderRepository = gitProviderRepository;
        this.accountIdentityQuery = accountIdentityQuery;
    }

    /** Resolves or provisions the first linked GitHub/GitLab identity. */
    @Transactional
    public Optional<User> resolveOrProvisionCurrentUser() {
        for (IdentityLinkView link : activeLinksForCurrentAccount()) {
            Optional<IdentityProvider> provider = gitProviderRepository.findById(link.gitProviderId());
            if (provider.isPresent()
                    && (provider.get().getType() == IdentityProviderType.GITHUB
                            || provider.get().getType() == IdentityProviderType.GITLAB)) {
                return Optional.of(resolveOrProvisionUser(link));
            }
        }
        return Optional.empty();
    }

    /**
     * Ensures the account has a GitLab actor for workspace-owner bootstrap.
     * Missing GitLab identity or conflicting saved profile data yields 409.
     */
    @Transactional
    public void ensureCurrentGitLabUserExists() {
        List<IdentityLinkView> links = activeLinksForCurrentAccount();

        IdentityLinkView gitLabLink = firstOfType(links, IdentityProviderType.GITLAB);
        if (gitLabLink != null) {
            resolveOrProvisionUser(gitLabLink);
            return;
        }

        if (firstOfType(links, IdentityProviderType.GITHUB) != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "You need to link your GitLab account before creating a GitLab workspace. Go to Settings → Linked Accounts to connect your GitLab identity.");
        }

        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "No GitLab identity found. Please link your GitLab account in Settings → Linked Accounts.");
    }

    private List<IdentityLinkView> activeLinksForCurrentAccount() {
        return SecurityUtils.getCurrentAccountId()
                .map(accountIdentityQuery::activeLinksForAccount)
                .orElseGet(List::of);
    }

    @Nullable
    private IdentityLinkView firstOfType(List<IdentityLinkView> links, IdentityProviderType type) {
        for (IdentityLinkView link : links) {
            IdentityProvider provider =
                    gitProviderRepository.findById(link.gitProviderId()).orElse(null);
            if (provider != null && provider.getType() == type) {
                return link;
            }
        }
        return null;
    }

    /** Returns the actor matching the verified provider subject, provisioning it only when absent. */
    private User resolveOrProvisionUser(IdentityLinkView link) {
        IdentityProvider provider = gitProviderRepository
                .findById(link.gitProviderId())
                .orElseThrow(() -> new IllegalStateException(
                        "git_provider row missing for IdentityLink.gitProviderId=" + link.gitProviderId()));
        long nativeId = parseSubject(link.subject(), provider.getType());
        Optional<User> existing = userRepository.findByNativeIdAndProviderId(nativeId, link.gitProviderId());
        if (existing.isPresent()) {
            // Signup profile fields are historical; never overwrite the provider-synced actor with them.
            return existing.get();
        }
        String login =
                (link.usernameAtSignup() != null && !link.usernameAtSignup().isBlank())
                        ? link.usernameAtSignup()
                        : link.subject();
        String name = (link.displayName() != null && !link.displayName().isBlank()) ? link.displayName() : login;
        String webUrl = (link.profileUrl() != null && !link.profileUrl().isBlank())
                ? link.profileUrl()
                : provider.getServerUrl() + "/" + login;
        String avatar = normalizeAvatar(link.avatarUrl(), provider.getServerUrl());

        Long userId = upsertUser(nativeId, login, name, avatar, webUrl, provider);
        accountIdentityQuery.linkExternalActor(link.identityLinkId(), userId);
        return userRepository
                .findById(userId)
                .orElseThrow(() -> new IllegalStateException("User not found after upsert: userId=" + userId));
    }

    private static long parseSubject(String subject, IdentityProviderType type) {
        try {
            return Long.parseLong(subject);
        } catch (NumberFormatException e) {
            // A mutable login cannot substitute for the provider's numeric actor id.
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Linked " + type
                            + " identity has a non-numeric subject; the account must be re-linked. Go to Settings → Linked Accounts.",
                    e);
        }
    }

    @Nullable
    private static String normalizeAvatar(@Nullable String avatarUrl, String serverUrl) {
        if (avatarUrl == null || avatarUrl.isBlank()) {
            return "";
        }
        return avatarUrl.startsWith("/") ? serverUrl + avatarUrl : avatarUrl;
    }

    private Long upsertUser(
            long nativeId,
            String login,
            String name,
            @Nullable String avatarUrl,
            String webUrl,
            IdentityProvider provider) {
        String safeName = name != null ? name : login;
        String safeAvatar = avatarUrl != null ? avatarUrl : "";
        Long providerId = Objects.requireNonNull(provider.getId(), "Identity provider must be persisted");

        userRepository.acquireLoginLock(login, providerId);
        Optional<User> loginOwner = userRepository.findByLoginAndProviderId(login, providerId);
        if (loginOwner.isPresent() && loginOwner.get().getNativeId() != nativeId) {
            // Only current provider evidence can reassign a login; a signup snapshot cannot prove a rename.
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Your saved provider username belongs to a different identity. Refresh or synchronize your provider profile before using account settings.");
        }
        userRepository.upsertUser(
                nativeId,
                providerId,
                login,
                safeName,
                safeAvatar,
                webUrl != null ? webUrl : "",
                User.Type.USER.name(),
                null,
                null,
                null);
        log.info(
                "Upserted authenticated git provider user: userLogin={}, nativeId={}, providerType={}",
                LoggingUtils.sanitizeForLog(login),
                nativeId,
                provider.getType());
        return userRepository
                .findByNativeIdAndProviderId(nativeId, providerId)
                .map(User::getId)
                .orElseThrow(() -> new IllegalStateException("User not found after upsert: nativeId=" + nativeId));
    }
}
