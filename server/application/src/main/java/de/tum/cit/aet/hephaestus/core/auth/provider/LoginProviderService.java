package de.tum.cit.aet.hephaestus.core.auth.provider;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.OidcIssuerPolicy;
import de.tum.cit.aet.hephaestus.core.security.OutlineOriginPolicy;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.core.security.ServerUrlValidator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * Owns the instance-scoped {@link LoginProvider} table: seeds the env-configured defaults on first
 * boot, is the single read path for building login {@code ClientRegistration}s, and backs
 * the instance-admin CRUD.
 *
 * <p>Seeding is idempotent and promote-once: a provider is created from {@code hephaestus.auth.login-
 * providers.*} only when it does not already exist, so env becomes the <em>seed</em>, never the live
 * source — an admin can edit or disable a seeded provider afterwards and the env value won't clobber it
 * on the next boot. Each entry is keyed by a stable, operator-chosen {@code registrationId} (e.g.
 * {@code github}, {@code gitlab}, {@code gitlab-lrz}) so {@code IdentityLink}s and the admin allowlist
 * keep resolving across reboots; multiple providers per instance seed in one boot (one per
 * {@code (type, baseUrl)} SCM instance).
 *
 * <p>Every mutation evicts the {@link LoginProviderClientRegistrationRepository} cache so an admin's
 * edit/enable/disable takes effect immediately rather than after the 60s TTL.
 *
 * <p>The three admin mutations write to the instance-scoped {@code auth_event} trail; env seeding does
 * not, having no authenticated principal behind it.
 */
@ConditionalOnServerRole
@Service
@WorkspaceAgnostic("Login providers are instance-global, not workspace-scoped")
public class LoginProviderService {

    static final String GITHUB_SCOPES = "read:user user:email";
    // GitLab is plain OAuth2, NOT OIDC — scope must NOT contain "openid" (see sanitizeScopesOrThrow).
    // "read_user" alone returns id + username + email from /api/v4/user. See ADR 0017.
    static final String GITLAB_SCOPES = "read_user";
    // "Sign in with Slack" is OIDC: the id_token carries the sub + verified team_id claim, and
    // "openid" is REQUIRED (it makes Spring take the OIDC path).
    static final String SLACK_SCOPES = "openid profile email";
    // Outline is plain OAuth2, NOT OIDC — scope must NOT contain "openid" (see sanitizeScopesOrThrow).
    // "read" is Outline's read-everything scope, sufficient for the POST /api/auth.info identity probe.
    static final String OUTLINE_SCOPES = "read";
    static final String OIDC_SCOPES = "openid profile email";
    private static final String GITHUB_COM = "https://github.com";
    // The single Slack instance. Canonical origin the seeded identity_provider + SlackMentorIdentityResolver key on.
    private static final String SLACK_COM = "https://slack.com";

    private static final Logger log = LoggerFactory.getLogger(LoginProviderService.class);

    private final LoginProviderRepository repository;
    private final LoginProviderClientRegistrationRepository registrationCache;
    private final AuthProperties authProperties;
    private final AuthEventLogger authEventLogger;
    private final ObjectMapper objectMapper;
    private final OutlineOriginPolicy outlineOriginPolicy;
    private final OidcIssuerPolicy oidcIssuerPolicy;
    private final GitProviderRegistry identities;

    public LoginProviderService(
            LoginProviderRepository repository,
            LoginProviderClientRegistrationRepository registrationCache,
            AuthProperties authProperties,
            AuthEventLogger authEventLogger,
            ObjectMapper objectMapper,
            OutlineOriginPolicy outlineOriginPolicy,
            OidcIssuerPolicy oidcIssuerPolicy,
            GitProviderRegistry identities) {
        this.repository = repository;
        this.registrationCache = registrationCache;
        this.authProperties = authProperties;
        this.authEventLogger = authEventLogger;
        this.objectMapper = objectMapper;
        this.outlineOriginPolicy = outlineOriginPolicy;
        this.oidcIssuerPolicy = oidcIssuerPolicy;
        this.identities = identities;
    }

    /** Enabled providers for the login page / discovery, stable order. */
    @Transactional(readOnly = true)
    public List<LoginProvider> listEnabled() {
        return repository.findByEnabledTrueOrderByDisplayNameAsc().stream()
                .filter(this::isApproved)
                .toList();
    }

    /** All providers (incl. disabled) for the admin UI, stable order. */
    @Transactional(readOnly = true)
    public List<LoginProvider> listAll() {
        return repository.findAll(Sort.by("displayName").ascending());
    }

    /**
     * The enabled provider behind a registration id, or empty when unknown/disabled — the login-begin
     * lookup ({@code AuthBeginController}): an unknown or disabled provider must not start an OAuth
     * flow, and the row's type drives the link-only gate.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<LoginProvider> findEnabled(String registrationId) {
        return repository
                .findByRegistrationId(registrationId)
                .filter(LoginProvider::isEnabled)
                .filter(this::isApproved);
    }

    @Transactional(readOnly = true)
    public LoginProvider require(String registrationId) {
        return loadRequired(registrationId);
    }

    private LoginProvider loadRequired(String registrationId) {
        return repository
                .findByRegistrationId(registrationId)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "no login provider: " + registrationId));
    }

    private LoginProvider loadRequiredForUpdate(String registrationId) {
        return repository
                .findByRegistrationIdForUpdate(registrationId)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "no login provider: " + registrationId));
    }

    /** Directory admission serializes with disabling, editing or deleting its instance-approved source. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public java.util.Optional<LoginProvider> findEnabledForUpdate(String registrationId) {
        return repository
                .findByRegistrationIdForUpdate(registrationId)
                .filter(LoginProvider::isEnabled)
                .filter(this::isApproved);
    }

    /** Create a new login provider (instance admin). The client secret is sealed at rest. */
    @Transactional
    public LoginProvider create(Draft draft) {
        String registrationId =
                draft.registrationId() == null ? "" : draft.registrationId().trim();
        if (!registrationId.matches("^[a-z][a-z0-9-]{1,62}$")) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "registrationId must be 2-63 chars: lowercase letter then lowercase letters, digits, or hyphens");
        }
        if (repository.existsByRegistrationId(registrationId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "a login provider '" + registrationId + "' already exists");
        }
        LoginProvider provider = new LoginProvider();
        provider.setRegistrationId(registrationId);
        provider.setType(draft.type());
        String displayName = blankToNull(draft.displayName());
        provider.setDisplayName(displayName == null ? registrationId : displayName);
        provider.setBaseUrl(resolveBaseUrl(draft.type(), draft.baseUrl()));
        provider.setClientId(requireValue(draft.clientId(), "clientId"));
        provider.setClientSecret(requireValue(draft.clientSecret(), "clientSecret"));
        provider.setScopes(resolveScopes(draft.type(), draft.scopes()));
        provider.setEnabled(true);
        provider.setSeededFromEnv(false);
        LoginProvider saved = persist(provider);
        audit(AuthEvent.EventType.LOGIN_PROVIDER_CREATED, saved, null);
        log.info("auth.login-provider: admin created '{}' ({})", registrationId, saved.getType());
        return saved;
    }

    /** Apply a partial update (only non-null fields). registrationId + type are immutable identity. */
    @Transactional
    public LoginProvider update(String registrationId, Patch patch) {
        LoginProvider provider = loadRequiredForUpdate(registrationId);
        // Field NAMES only — this list goes into the audit trail, so it must stay free of values.
        List<String> changed = new ArrayList<>();
        if (patch.displayName() != null && !patch.displayName().isBlank()) {
            provider.setDisplayName(patch.displayName().trim());
            changed.add("displayName");
        }
        if (patch.baseUrl() != null && !patch.baseUrl().isBlank()) {
            String baseUrl = resolveBaseUrl(provider.getType(), patch.baseUrl());
            if (provider.getType() == LoginProvider.ProviderType.OIDC
                    && !provider.getBaseUrl().equals(baseUrl)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "An OIDC issuer is immutable; add a separate login provider to change realms");
            }
            provider.setBaseUrl(baseUrl);
            changed.add("baseUrl");
        }
        if (patch.clientId() != null && !patch.clientId().isBlank()) {
            provider.setClientId(patch.clientId().trim());
            changed.add("clientId");
        }
        // Write-only secret: a null/blank value leaves the sealed secret unchanged.
        if (patch.clientSecret() != null && !patch.clientSecret().isBlank()) {
            provider.setClientSecret(patch.clientSecret());
            changed.add("clientSecret");
        }
        if (patch.scopes() != null && !patch.scopes().isBlank()) {
            provider.setScopes(sanitizeScopesOrThrow(provider.getType(), patch.scopes()));
            changed.add("scopes");
        }
        if (patch.enabled() != null && !patch.enabled() && provider.isEnabled()) {
            requireNotLastEnabled(registrationId, "disable");
            provider.setEnabled(false);
            changed.add("enabled");
        } else if (patch.enabled() != null && patch.enabled()) {
            provider.setEnabled(true);
            changed.add("enabled");
        }
        LoginProvider saved = persist(provider);
        audit(AuthEvent.EventType.LOGIN_PROVIDER_UPDATED, saved, changed);
        log.info("auth.login-provider: admin updated '{}' (enabled={})", registrationId, saved.isEnabled());
        return saved;
    }

    /** Directory approval is a distinct operator action; interactive sign-in credentials are never reused. */
    @Transactional
    public LoginProvider approveDirectoryGroups(String registrationId, Set<String> groupIds) {
        if (!SecurityUtils.isSuperAdmin())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Instance administrator access required");
        LoginProvider provider = loadRequiredForUpdate(registrationId);
        if (provider.getType() != LoginProvider.ProviderType.OIDC || !isApproved(provider))
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "Approve an organizational OIDC issuer first");
        if (groupIds.size() > 100
                || groupIds.stream()
                        .anyMatch(id -> id.isBlank()
                                || id.length() > 255
                                || id.contains("/")
                                || id.contains("\\")
                                || id.equals(".")
                                || id.equals("..")))
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "Provide at most 100 immutable Keycloak group IDs");
        if (!groupIds.isEmpty()) identities.resolveProviderId("OIDC", provider.getBaseUrl());
        provider.setDirectoryGroupIds(Set.copyOf(groupIds));
        LoginProvider saved = persist(provider);
        audit(AuthEvent.EventType.LOGIN_PROVIDER_UPDATED, saved, List.of("directoryGroupIds"));
        return saved;
    }

    /** Delete a login provider. Refuses to remove the last enabled one (would lock everyone out). */
    @Transactional
    public void delete(String registrationId) {
        LoginProvider provider = loadRequiredForUpdate(registrationId);
        if (provider.isEnabled()) {
            requireNotLastEnabled(registrationId, "delete");
        }
        repository.delete(provider);
        registrationCache.evict(registrationId);
        audit(AuthEvent.EventType.LOGIN_PROVIDER_DELETED, provider, null);
        log.info("auth.login-provider: admin deleted '{}'", registrationId);
    }

    /**
     * Record an admin login-provider mutation on the {@code auth_event} trail. {@code changed} carries
     * the field names a PATCH touched — never their values, so no secret can reach the trail.
     */
    private void audit(AuthEvent.EventType type, LoginProvider provider, @Nullable List<String> changed) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("registrationId", provider.getRegistrationId());
        details.put("type", provider.getType().name());
        details.put("enabled", provider.isEnabled());
        if (changed != null) {
            details.put("changed", changed);
        }
        authEventLogger
                .event(type, AuthEvent.Result.SUCCESS)
                .actingAccount(SecurityUtils.getCurrentAccountId().orElse(null))
                .details(objectMapper.writeValueAsString(details))
                .record();
    }

    /**
     * A seed slot is <em>silence</em> (both credential halves blank — "this deployment has no wiki") or a
     * <em>promise</em> (credentials present). A promise that cannot be kept — a half-filled credential, an
     * unusable base URL — is an operator mistake that used to vanish into a single WARN, leaving the admin
     * with no provider and no reason why. It is now reported at ERROR with the exact knob to fix.
     *
     * <p>Deliberately non-fatal: this listener runs on {@link ApplicationReadyEvent}, and an exception here
     * aborts startup. One misconfigured optional integration must not take the whole app down — so we shout,
     * we don't die.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedFromEnvOnStartup() {
        // Sorted by registration id for deterministic seeding when two entries collide on (type, baseUrl).
        Set<String> seededInstances = new HashSet<>();
        new TreeMap<>(authProperties.loginProviders()).forEach((registrationId, seed) -> {
            if (seed == null) {
                return;
            }
            String id = registrationId == null ? "" : registrationId.trim();
            if (seed.partiallyConfigured()) {
                // Half a credential: seeding it would offer an ENABLED provider whose every OAuth exchange
                // 401s at the IdP with an opaque error. Skip it — and say exactly which env var is missing.
                log.error(
                        "auth.login-provider: NOT seeding '{}' — it is half-configured: {} is blank. "
                                + "Set {}, or clear the other half to disable this provider entirely.",
                        id,
                        seed.missingCredentialField(),
                        envKnobFor(id, seed.missingCredentialField()));
                return;
            }
            if (!seed.configured()) {
                return; // blank credentials → provider omitted (credential-less pods still boot)
            }
            if (!id.matches("^[a-z][a-z0-9-]{1,62}$")) {
                log.error(
                        "auth.login-provider: NOT seeding '{}' — invalid registration id. It must be 2-63 chars: "
                                + "a lowercase letter followed by lowercase letters, digits, or hyphens (e.g. 'gitlab-lrz').",
                        registrationId);
                return;
            }
            if (repository.existsByRegistrationId(id)) {
                return; // seed-once (see class javadoc)
            }
            String baseUrl;
            try {
                baseUrl = resolveBaseUrl(seed.type(), seed.baseUrl());
            } catch (ResponseStatusException e) {
                // The common self-hosted-Outline / self-hosted-GitLab trip-wire: credentials set, base URL
                // blank, http://, or a private/internal address (rejected by the SSRF guard).
                log.error(
                        "auth.login-provider: NOT seeding '{}' ({}) — its base URL is unusable: {}. "
                                + "Set {} to the instance's public HTTPS origin (e.g. https://wiki.example.com).",
                        id,
                        seed.type(),
                        e.getReason(),
                        envKnobFor(id, "base-url"));
                return;
            }
            // One login app per SCM instance (uq on type+base_url): skip a duplicate so a misconfiguration
            // can't crash startup on a constraint violation.
            if (!seededInstances.add(seed.type() + "|" + baseUrl)
                    || repository.existsByTypeAndBaseUrl(seed.type(), baseUrl)) {
                log.warn(
                        "auth.login-provider: skipping seed '{}' — a {} provider for {} already exists",
                        id,
                        seed.type(),
                        baseUrl);
                return;
            }
            String displayName =
                    seed.displayName().isBlank() ? id : seed.displayName().trim();
            seed(
                    id,
                    seed.type(),
                    displayName,
                    baseUrl,
                    seed.clientId().trim(),
                    seed.clientSecret(),
                    resolveScopes(seed.type(), null));
            log.info("auth.login-provider: seeded '{}' ({} {}) from env", id, seed.type(), baseUrl);
        });
    }

    /**
     * The env var that feeds a shipped seed slot's field, so a misconfiguration diagnostic names the knob the
     * operator actually sets ({@code OUTLINE_OAUTH_CLIENT_SECRET}), not an abstract property path. Slots the
     * operator declared themselves (e.g. {@code gitlab-lrz}) have no fixed env var — fall back to the canonical
     * property path, which is always correct. Keep in sync with {@code application.yml}'s
     * {@code hephaestus.auth.login-providers} block.
     */
    private static final Map<String, String> SHIPPED_SLOT_ENV_PREFIX = Map.of(
            "github", "GITHUB_OAUTH", "gitlab", "GITLAB_OAUTH", "slack", "SLACK_OAUTH", "outline", "OUTLINE_OAUTH");

    private static String envKnobFor(String registrationId, String field) {
        String prefix = SHIPPED_SLOT_ENV_PREFIX.get(registrationId);
        if (prefix == null) {
            return "hephaestus.auth.login-providers." + registrationId + "." + field;
        }
        return prefix + "_" + field.toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private void seed(
            String registrationId,
            LoginProvider.ProviderType type,
            String displayName,
            String baseUrl,
            String clientId,
            String clientSecret,
            String scopes) {
        LoginProvider provider = new LoginProvider();
        provider.setRegistrationId(registrationId);
        provider.setType(type);
        provider.setDisplayName(displayName);
        provider.setBaseUrl(baseUrl);
        provider.setClientId(clientId);
        provider.setClientSecret(clientSecret);
        provider.setScopes(scopes);
        provider.setEnabled(true);
        provider.setSeededFromEnv(true);
        repository.save(provider);
    }

    private LoginProvider persist(LoginProvider provider) {
        // Flush here rather than at commit: an update mutates a managed entity, so a UNIQUE(type,
        // base_url) violation would otherwise surface after the caller has already recorded a SUCCESS.
        // That row is written in its own REQUIRES_NEW transaction, so it outlives the rollback and the
        // trail keeps a change that never happened.
        LoginProvider saved = repository.saveAndFlush(provider);
        registrationCache.evict(saved.getRegistrationId());
        return saved;
    }

    /**
     * GitHub login is github.com only (GHE login is out of scope; the registration builder hardcodes
     * github.com endpoints). For GitLab and Outline (self-hosted instances), validate the supplied
     * base URL (HTTPS + SSRF guard).
     */
    private String resolveBaseUrl(LoginProvider.ProviderType type, @Nullable String baseUrl) {
        if (type == LoginProvider.ProviderType.GITHUB) {
            return GITHUB_COM;
        }
        if (type == LoginProvider.ProviderType.SLACK) {
            // Slack login is slack.com only (the registration builder hardcodes the slack.com OIDC endpoints).
            return SLACK_COM;
        }
        String value = baseUrl == null ? "" : baseUrl.trim();
        if (type == LoginProvider.ProviderType.OIDC) {
            if (!oidcIssuerPolicy.allows(value)) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "OIDC issuer is not approved by the instance operator");
            }
            return value;
        }
        try {
            ServerUrlValidator.validate(value);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid base URL: " + e.getMessage(), e);
        }
        if (type == LoginProvider.ProviderType.OUTLINE && !outlineOriginPolicy.allows(value)) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "Outline origin is not approved by the instance operator");
        }
        return value;
    }

    private boolean isApproved(LoginProvider provider) {
        return (provider.getType() != LoginProvider.ProviderType.OUTLINE
                        || outlineOriginPolicy.allows(provider.getBaseUrl()))
                && (provider.getType() != LoginProvider.ProviderType.OIDC
                        || oidcIssuerPolicy.allows(provider.getBaseUrl()));
    }

    private static String resolveScopes(LoginProvider.ProviderType type, @Nullable String scopes) {
        if (scopes != null && !scopes.isBlank()) {
            return sanitizeScopesOrThrow(type, scopes);
        }
        return switch (type) {
            case GITHUB -> GITHUB_SCOPES;
            case GITLAB -> GITLAB_SCOPES;
            case SLACK -> SLACK_SCOPES;
            case OUTLINE -> OUTLINE_SCOPES;
            case OIDC -> OIDC_SCOPES;
        };
    }

    /**
     * GitLab and Outline use the plain OAuth2 flow (userInfo = /api/v4/user resp. /api/auth.info); an
     * {@code openid} scope flips Spring to the OIDC path and 500s the callback (no jwkSetUri is
     * configured — Outline is not an OIDC provider at all). Reject it with an actionable 422 so an
     * admin editing the provider can't silently brick sign-in. See GITLAB_SCOPES / OUTLINE_SCOPES +
     * ADR 0017.
     */
    private static String sanitizeScopesOrThrow(LoginProvider.ProviderType type, String scopes) {
        String trimmed = scopes.trim();
        if (type == LoginProvider.ProviderType.OIDC
                && !java.util.Arrays.asList(trimmed.split("\\s+")).contains("openid")) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "OIDC login requires the 'openid' scope");
        }
        if (type == LoginProvider.ProviderType.GITLAB || type == LoginProvider.ProviderType.OUTLINE) {
            String replacement = type == LoginProvider.ProviderType.GITLAB ? "'read_user'" : "'read'";
            for (String scope : trimmed.split("\\s+")) {
                if (scope.equalsIgnoreCase("openid")) {
                    throw new ResponseStatusException(
                            HttpStatus.UNPROCESSABLE_ENTITY,
                            type + " login uses the plain OAuth2 flow — the scope must not contain 'openid' (use "
                                    + replacement
                                    + ")");
                }
            }
        }
        return trimmed;
    }

    /**
     * The lockout guard, counting only providers that can actually sign someone in. A link-only provider
     * ({@link LoginProvider.ProviderType#isLinkOnly()} — Slack, Outline) is BY CONSTRUCTION never a sign-in
     * method: it is filtered off the login picker and rejected at flow begin. Counting it here would (a)
     * refuse to remove the sole Outline provider on an instance that has one, and (b) worse, let an admin
     * delete the last REAL sign-in provider as long as a link-only one remained — the guard would see a
     * non-empty "enabled" list and wave it through. Both fall out once the count is sign-in-capable only.
     */
    private void requireNotLastEnabled(String registrationId, String verb) {
        List<LoginProvider> enabled = repository.findByEnabledTrueOrderByDisplayNameAsc().stream()
                .filter(p -> p.getType() != null && !p.getType().isLinkOnly())
                .filter(this::isApproved)
                .toList();
        boolean isLast = enabled.stream().allMatch(p -> p.getRegistrationId().equals(registrationId));
        if (!enabled.isEmpty() && isLast) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "cannot " + verb + " the last enabled login provider — users would be unable to sign in");
        }
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private static String requireValue(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, field + " is required");
        }
        return value.trim();
    }

    /** Create command (instance admin). {@code scopes} may be null → defaulted by {@code type}. */
    public record Draft(
            String registrationId,
            LoginProvider.ProviderType type,
            @Nullable String displayName,
            @Nullable String baseUrl,
            String clientId,
            String clientSecret,
            @Nullable String scopes) {}

    /** Partial-update command; every field null = leave unchanged. */
    public record Patch(
            @Nullable String displayName,
            @Nullable String baseUrl,
            @Nullable String clientId,
            @Nullable String clientSecret,
            @Nullable String scopes,
            @Nullable Boolean enabled) {}
}
