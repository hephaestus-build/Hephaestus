package de.tum.cit.aet.hephaestus.core.auth.oauth;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProvider;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProviderRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.event.AccountSecurityChangedEvent;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves (or just-in-time creates) the {@link Account} for a federated login, and
 * attaches {@link IdentityLink}s.
 *
 * <p><strong>nOAuth defence:</strong> lookup is always {@code (provider, subject)} via
 * {@link IdentityLinkRepository#findActiveByProviderSubject}. Email is captured for
 * forensics only, never used to resolve an account.
 */
@ConditionalOnServerRole
@Service
@WorkspaceAgnostic("Account provisioning is user-scoped, keyed by (provider, subject)")
public class AccountProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(AccountProvisioningService.class);

    private final AccountRepository accountRepository;
    private final IdentityLinkRepository identityLinkRepository;
    private final GitProviderRegistry gitProviderRegistry;
    private final LoginProviderRepository loginProviderRepository;
    private final VerifiedEmailResolver verifiedEmailResolver;
    private final AccountJitCreator accountJitCreator;
    private final AdminBootstrapPolicy adminBootstrapPolicy;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    public AccountProvisioningService(
            AccountRepository accountRepository,
            IdentityLinkRepository identityLinkRepository,
            GitProviderRegistry gitProviderRegistry,
            LoginProviderRepository loginProviderRepository,
            VerifiedEmailResolver verifiedEmailResolver,
            AccountJitCreator accountJitCreator,
            AdminBootstrapPolicy adminBootstrapPolicy,
            Clock clock,
            ApplicationEventPublisher eventPublisher) {
        this.accountRepository = accountRepository;
        this.identityLinkRepository = identityLinkRepository;
        this.gitProviderRegistry = gitProviderRegistry;
        this.loginProviderRepository = loginProviderRepository;
        this.verifiedEmailResolver = verifiedEmailResolver;
        this.accountJitCreator = accountJitCreator;
        this.adminBootstrapPolicy = adminBootstrapPolicy;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Outcome of {@link #resolveOrProvision}. {@code identityLinked} is true ONLY when a NEW identity
     * was attached to an existing account (LINK mode) — so the caller can audit IDENTITY_LINKED for a
     * genuine link and LOGIN for a returning login / re-affirm / JIT create. A LINK-mode flow that merely
     * re-affirms an already-linked identity persists no new link, so it reports {@code false}.
     */
    public record ProvisionResult(Account account, boolean identityLinked) {}

    /**
     * Resolve the account for a completed OAuth login: returning login (link exists),
     * link-mode (attach to current account), or fresh JIT creation.
     */
    @Transactional
    public ProvisionResult resolveOrProvision(
            String registrationId, String subject, OAuth2User principal, AuthIntentCookie.@Nullable Intent intent) {
        LoginProvider provider = requireLoginProvider(registrationId);
        long providerId =
                gitProviderRegistry.resolveProviderId(provider.getType().name(), provider.getBaseUrl());
        AuthIntentCookie.Intent.Mode mode = (intent != null) ? intent.mode() : AuthIntentCookie.Intent.Mode.LOGIN;
        // Link-only providers (Slack, Outline) may only ATTACH an identity to an authenticated account —
        // never act as a primary login. AuthBeginController rejects these at flow begin; this is the
        // defence-in-depth re-check at the callback.
        if (provider.getType().isLinkOnly() && mode != AuthIntentCookie.Intent.Mode.LINK) {
            throw new LinkOnlyProviderLoginException(registrationId);
        }
        String teamId = teamIdOf(principal);
        // Both link-only providers are multi-tenant: a Slack U… / Outline user UUID is only unique within
        // its team, so a null teamId would alias identities across tenants — fail closed.
        if (provider.getType().isLinkOnly() && (teamId == null || teamId.isBlank())) {
            throw new IllegalStateException(provider.getType() + " identity is missing team_id");
        }

        IdentityLink link = identityLinkRepository
                .findActiveByProviderSubject(providerId, subject, teamId)
                .orElse(null);

        if (link != null) {
            // Collision guard (secure account linking): if this identity is already an ACTIVE link, a
            // LINK-mode flow may only re-affirm it for the SAME account. Returning a DIFFERENT account
            // here would log the operator into someone else's account (or rebind a victim's identity) —
            // an account-takeover vector. LOGIN mode is unaffected (the identity owns the account).
            if (mode == AuthIntentCookie.Intent.Mode.LINK
                    && intent != null
                    && intent.linkingAccountId() != null
                    && !Objects.requireNonNull(link.getAccount().getId()).equals(intent.linkingAccountId())) {
                throw new AccountLinkConflictException(
                        registrationId, subject, link.getAccount().getId());
            }
            identityLinkRepository.touchLastLogin(link.getId(), clock.instant());
            log.info(
                    "auth.success: returning login provider={} accountId={}",
                    registrationId,
                    link.getAccount().getId());
            return new ProvisionResult(
                    promoteIfBootstrapAdmin(link.getAccount(), registrationId, subject, loginOf(principal)), false);
        }

        if (mode == AuthIntentCookie.Intent.Mode.LINK) {
            // Defense in depth: AuthBeginController already rejects link mode without a valid session
            // (it binds linkingAccountId from the validated access cookie), so a null binding here means
            // a programming error, not user input — fail closed rather than JIT-create an orphan account.
            if (intent == null || intent.linkingAccountId() == null) {
                throw new IllegalStateException("auth.link: link mode requires an authenticated account binding");
            }
            Account account = accountRepository
                    .findById(intent.linkingAccountId())
                    .orElseThrow(() -> new IllegalStateException(
                            "auth.link: linkingAccountId=" + intent.linkingAccountId() + " not found"));
            IdentityLink linked = newIdentityLink(account, providerId, subject, teamId, principal);
            linked.setLinkedVia(IdentityLink.LinkedVia.MANUAL_LINK);
            identityLinkRepository.save(linked);
            eventPublisher.publishEvent(new AccountSecurityChangedEvent(
                    Objects.requireNonNull(account.getId()),
                    AccountSecurityChangedEvent.Kind.IDENTITY_LINKED,
                    clock.instant()));
            log.info("auth.success: linked provider={} to existing accountId={}", registrationId, account.getId());
            return new ProvisionResult(account, true);
        }

        Account account = new Account(displayName(principal, subject));
        VerifiedEmailResolver.ResolvedEmail resolvedEmail = verifiedEmailResolver.resolve(registrationId, principal);
        account.setPrimaryEmail(resolvedEmail.email());
        // nOAuth defence: email is contact-only and is NEVER a lookup key. Stamp the verified timestamp
        // ONLY when the IdP attested verification (OIDC email_verified==true, or GitHub's primary+verified
        // /user/emails entry); otherwise it stays null = unverified.
        if (resolvedEmail.verified()) {
            account.setPrimaryEmailVerifiedAt(clock.instant());
        }
        IdentityLink newLink = newIdentityLink(account, providerId, subject, teamId, principal);
        try {
            // The insert runs in AccountJitCreator's REQUIRES_NEW transaction so a unique-constraint
            // loss rolls back ONLY that inner tx — this (the caller's) tx stays usable for the
            // read-after-conflict below.
            Account created = accountJitCreator.create(account, newLink);
            log.info(
                    "auth.success: JIT created accountId={} via provider={} emailVerified={}",
                    created.getId(),
                    registrationId,
                    resolvedEmail.verified());
            String login = loginOf(principal);
            Account result = promoteIfBootstrapAdmin(created, registrationId, subject, login);
            if (result.getAppRole() != Account.AppRole.APP_ADMIN && adminBootstrapPolicy.isConfigured()) {
                // Cold-start aid: a new account on an allowlist-configured instance that did NOT match.
                // Logs the exact identity so a mis-listed first admin can self-diagnose in one line
                // instead of silently landing as a plain USER with no Admin nav.
                log.info(
                        "auth.bootstrap: new accountId={} did NOT match bootstrap-admins (provider={} subject={} username=@{}). "
                                + "Add 'provider:@username' or 'provider:subject' to grant APP_ADMIN.",
                        result.getId(),
                        registrationId,
                        subject,
                        login);
            }
            return new ProvisionResult(result, false); // fresh JIT login, not a link onto an existing account
        } catch (DataIntegrityViolationException e) {
            // First-login race: a concurrent login already created this identity and won the
            // uq_identity_link_provider_subject_team insert. Fail closed by reading the now-existing
            // active link and returning the winner's account — never a second orphan account.
            return identityLinkRepository
                    .findActiveByProviderSubject(providerId, subject, teamId)
                    .map(IdentityLink::getAccount)
                    .map(winner -> {
                        log.info(
                                "auth.success: JIT race resolved — reused concurrently-created accountId={} via provider={}",
                                winner.getId(),
                                registrationId);
                        return new ProvisionResult(
                                promoteIfBootstrapAdmin(winner, registrationId, subject, loginOf(principal)), false);
                    })
                    .orElseThrow(() -> new IllegalStateException(
                            "auth.success: JIT create lost the race for provider=" + registrationId
                                    + " subject="
                                    + subject
                                    + " but no active link is visible (constraint/transaction anomaly)",
                            e));
        }
    }

    /**
     * Resolve the {@code git_provider} row id for a login registration: look up the instance-scoped
     * {@code login_provider} row (this module owns it), then let the integration-side registry upsert
     * the provider row from its {@code (type, baseUrl)} — keeping the IdentityProvider entity out of auth.
     */
    private LoginProvider requireLoginProvider(String registrationId) {
        return loginProviderRepository
                .findByRegistrationId(registrationId)
                .orElseThrow(() -> new IllegalArgumentException("unknown login registrationId: " + registrationId));
    }

    /**
     * Idempotent, promote-only instance-admin bootstrap: if the resolved login is on the
     * {@code hephaestus.auth.bootstrap-admins} allowlist and the account is not already APP_ADMIN,
     * promote it. Runs inside the login transaction so the role is committed before the JWT is minted
     * (so {@code admin} lands on the first token, not the second). Never demotes — demotion stays a
     * deliberate {@code /admin/users} action.
     */
    private Account promoteIfBootstrapAdmin(
            Account account, String registrationId, String subject, @Nullable String login) {
        if (account.getAppRole() != Account.AppRole.APP_ADMIN
                && adminBootstrapPolicy.shouldPromote(registrationId, subject, login)) {
            account.setAppRole(Account.AppRole.APP_ADMIN);
            accountRepository.save(account);
            eventPublisher.publishEvent(new AccountSecurityChangedEvent(
                    Objects.requireNonNull(account.getId()),
                    AccountSecurityChangedEvent.Kind.APP_ROLE_CHANGED,
                    clock.instant()));
            log.info(
                    "auth.bootstrap: promoted accountId={} to APP_ADMIN via bootstrap-admins allowlist (provider={})",
                    account.getId(),
                    registrationId);
        }
        return account;
    }

    /** Git login the user authenticated with — the value matched by {@code provider:@username} entries. */
    private static @Nullable String loginOf(OAuth2User principal) {
        return stringAttr(principal, "login", "preferred_username", "username");
    }

    private IdentityLink newIdentityLink(
            Account account, long providerId, String subject, @Nullable String teamId, OAuth2User principal) {
        IdentityLink link = new IdentityLink();
        link.setAccount(account);
        link.setProviderId(providerId);
        link.setSubject(subject);
        link.setTeamId(teamId);
        link.setUsernameAtSignup(stringAttr(principal, "login", "preferred_username", "username"));
        link.setEmailAtSignup(email(principal));
        link.setDisplayName(stringAttr(principal, "name", "display_name"));
        link.setAvatarUrl(stringAttr(principal, "avatar_url", "picture"));
        link.setProfileUrl(stringAttr(principal, "html_url", "web_url", "profile"));
        return link;
    }

    private static String displayName(OAuth2User principal, String subject) {
        String name = stringAttr(principal, "name", "display_name", "login", "preferred_username");
        return (name != null && !name.isBlank()) ? name : "user-" + subject;
    }

    private static @Nullable String email(OAuth2User principal) {
        return stringAttr(principal, "email");
    }

    /**
     * The multi-instance IdP tenant id, or {@code null} for single-tenant IdPs (GitHub/GitLab, which emit
     * no team claim). "Sign in with Slack" (OIDC) surfaces the workspace as the {@code https://slack.com/team_id}
     * claim (flat) or a nested {@code team.id} — check both. Outline rides the flat {@code team_id} attribute
     * ({@code OutlineAuthInfoUserService} flattens {@code data.team.id} onto it). Kept general: any future
     * multi-tenant IdP that emits a {@code team_id}/{@code tenant_id} claim keys correctly without a special case.
     */
    private static @Nullable String teamIdOf(OAuth2User principal) {
        String flat = stringAttr(principal, "https://slack.com/team_id", "team_id", "tenant_id", "tid");
        if (flat != null) {
            return flat;
        }
        Object team = principal.getAttributes().get("team");
        if (team == null) {
            team = principal.getAttributes().get("https://slack.com/team");
        }
        if (team instanceof Map<?, ?> teamMap && teamMap.get("id") instanceof String id && !id.isBlank()) {
            return id;
        }
        return null;
    }

    private static @Nullable String stringAttr(OAuth2User principal, String... keys) {
        Map<String, Object> attrs = principal.getAttributes();
        for (String k : keys) {
            if (attrs.get(k) instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }
}
