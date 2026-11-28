package de.tum.cit.aet.hephaestus.core.auth.oauth;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.PeopleRegistryService;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProvider;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProviderRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.event.AccountSecurityChangedEvent;
import de.tum.cit.aet.hephaestus.core.event.RoleChangedEvent;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolve accounts by {@link IdentityRepository#findActiveByProviderSubject}; email is contact data, never ownership proof. */
@ConditionalOnServerRole
@Service
@WorkspaceAgnostic("Account provisioning is user-scoped, keyed by (provider, subject)")
public class AccountProvisioningService {
    /** The sign-in refusal code for an account that an instance admin blocked. */
    public static final String ACCOUNT_BLOCKED = "account_blocked";

    private static final Logger log = LoggerFactory.getLogger(AccountProvisioningService.class);

    private final AccountRepository accountRepository;
    private final IdentityRepository identityRepository;
    private final GitProviderRegistry gitProviderRegistry;
    private final LoginProviderRepository loginProviderRepository;
    private final VerifiedEmailResolver verifiedEmailResolver;
    private final AccountJitCreator accountJitCreator;
    private final AdminBootstrapPolicy adminBootstrapPolicy;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;
    private final PersonDataWriteFence writeFence;
    private final PeopleRegistryService people;

    public AccountProvisioningService(
            AccountRepository accountRepository,
            IdentityRepository identityRepository,
            GitProviderRegistry gitProviderRegistry,
            LoginProviderRepository loginProviderRepository,
            VerifiedEmailResolver verifiedEmailResolver,
            AccountJitCreator accountJitCreator,
            AdminBootstrapPolicy adminBootstrapPolicy,
            Clock clock,
            ApplicationEventPublisher eventPublisher,
            PersonDataWriteFence writeFence,
            PeopleRegistryService people) {
        this.accountRepository = accountRepository;
        this.identityRepository = identityRepository;
        this.gitProviderRegistry = gitProviderRegistry;
        this.loginProviderRepository = loginProviderRepository;
        this.verifiedEmailResolver = verifiedEmailResolver;
        this.accountJitCreator = accountJitCreator;
        this.adminBootstrapPolicy = adminBootstrapPolicy;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
        this.writeFence = writeFence;
        this.people = people;
    }

    /**
     * Outcome of {@link #resolveOrProvision}. {@code identityLinked} is true ONLY when a NEW
     * identity was attached to an existing account (LINK mode) — so the caller can audit
     * IDENTITY_LINKED for a genuine link and LOGIN for a returning login / re-affirm / JIT create.
     * A LINK-mode flow that merely re-affirms an already-linked identity persists no new link, so
     * it reports {@code false}.
     */
    public record ProvisionResult(Account account, boolean identityLinked) {}

    @Transactional
    public ProvisionResult resolveOrProvision(
            String registrationId, String subject, OAuth2User principal, AuthIntentCookie.@Nullable Intent intent) {
        LoginProvider provider = requireLoginProvider(registrationId);
        boolean oidc = provider.getType() == LoginProvider.ProviderType.OIDC;
        // Before any write: the token's issuer keys the identity's namespace, and a registration cached before
        // another server replaced this row could be another type's.
        if (oidc) requireIssuerOf(provider, principal);
        long providerId =
                gitProviderRegistry.resolveProviderId(provider.getType().name(), provider.getBaseUrl());
        AuthIntentCookie.Intent.Mode mode = (intent != null) ? intent.mode() : AuthIntentCookie.Intent.Mode.LOGIN;
        // Link-only providers (Slack, Outline) may only ATTACH an identity to an authenticated
        // account —
        // never act as a primary login. AuthBeginController rejects these at flow begin; this is
        // the
        // defence-in-depth re-check at the callback.
        if (provider.getType().isLinkOnly() && mode != AuthIntentCookie.Intent.Mode.LINK) {
            throw new LinkOnlyProviderLoginException(registrationId);
        }
        // An OIDC subject is unique within its issuer, so a tenant claim such as Entra's tid never splits it.
        String teamId = oidc ? null : teamIdOf(principal);
        // A username can change and is not unique (OpenID Connect Core § 5.7), so it grants no bootstrap admin.
        String bootstrapLogin = oidc ? null : loginOf(principal);
        // Both link-only providers are multi-tenant: a Slack U… / Outline user UUID is only unique
        // within
        // its team, so a null teamId would alias identities across tenants — fail closed.
        if (provider.getType().isLinkOnly() && (teamId == null || teamId.isBlank())) {
            throw new IllegalStateException(provider.getType() + " identity is missing team_id");
        }

        var verifiedIdentity = new PersonIdentity(providerId, subject, teamId);
        if (!writeFence.holdForWrite(List.of(verifiedIdentity))) {
            throw new OAuth2AuthenticationException("identity_processing_suppressed");
        }

        Identity link = identityRepository
                .findActiveByProviderSubject(providerId, subject, teamId)
                .orElseGet(() ->
                        identityRepository.findByExactKey(providerId, subject, PersonIdentity.teamKey(teamId)).stream()
                                .findFirst()
                                .orElse(null));

        if (link != null) {
            // Collision guard (secure account linking): if this identity is already an ACTIVE link,
            // a
            // LINK-mode flow may only re-affirm it for the SAME account. Returning a DIFFERENT
            // account
            // here would log the operator into someone else's account (or rebind a victim's
            // identity) —
            // an account-takeover vector. LOGIN mode is unaffected (the identity owns the account).
            Account account = link.getAccount();
            long accountId = Objects.requireNonNull(account.getId());
            @Nullable Long linkingAccountId = intent == null ? null : intent.linkingAccountId();
            boolean absorbed = false;
            boolean reactivated = link.getDisabledAt() != null;
            if (mode == AuthIntentCookie.Intent.Mode.LINK
                    && linkingAccountId != null
                    && accountId != linkingAccountId) {
                if (account.getStatus() != Account.Status.UNCLAIMED)
                    throw new AccountLinkConflictException(registrationId, subject, accountId);
                people.absorbUnclaimed(new VerifiedAccountClaim(linkingAccountId, accountId, verifiedIdentity));
                // Bulk absorption can detach the identity read before the move.
                accountId = linkingAccountId;
                account = accountRepository.findById(accountId).orElseThrow();
                absorbed = true;
            }
            if ((absorbed || account.getStatus() == Account.Status.UNCLAIMED || reactivated)
                    && people.claim(verifiedIdentity) != accountId) {
                throw new OAuth2AuthenticationException("identity_processing_suppressed");
            }
            requireActiveAccountForWrite(accountId);
            identityRepository.touchLastLogin(link.getId(), clock.instant());
            log.info("auth.success: returning login provider={} accountId={}", registrationId, accountId);
            boolean linkedAuthority = absorbed || (mode == AuthIntentCookie.Intent.Mode.LINK && reactivated);
            if (linkedAuthority) publishIdentityLinked(accountId);
            return new ProvisionResult(
                    promoteIfBootstrapAdmin(account, registrationId, subject, bootstrapLogin), linkedAuthority);
        }

        if (mode == AuthIntentCookie.Intent.Mode.LINK) {
            // Defense in depth: AuthBeginController already rejects link mode without a valid
            // session
            // (it binds linkingAccountId from the validated access cookie), so a null binding here
            // means
            // a programming error, not user input — fail closed rather than JIT-create an orphan
            // account.
            if (intent == null || intent.linkingAccountId() == null) {
                throw new IllegalStateException("auth.link: link mode requires an authenticated account binding");
            }
            long sourceId = people.ensureHuman(verifiedIdentity, displayName(principal, subject));
            requireActiveAccountForWrite(intent.linkingAccountId());
            if (sourceId != intent.linkingAccountId()) {
                people.absorbUnclaimed(new VerifiedAccountClaim(intent.linkingAccountId(), sourceId, verifiedIdentity));
            }
            people.claim(verifiedIdentity);
            Account account =
                    accountRepository.findById(intent.linkingAccountId()).orElseThrow();
            var linked = identityRepository
                    .findActiveByProviderSubject(providerId, subject, teamId)
                    .orElseThrow();
            linked.setLinkedVia(Identity.LinkedVia.MANUAL);
            linked.setLastLoginAt(clock.instant());
            identityRepository.save(linked);
            publishIdentityLinked(intent.linkingAccountId());
            return new ProvisionResult(account, true);
        }

        Account account = new Account(displayName(principal, subject));
        VerifiedEmailResolver.ResolvedEmail resolvedEmail = verifiedEmailResolver.resolve(registrationId, principal);
        account.setPrimaryEmail(resolvedEmail.email());
        // nOAuth defence: email is contact-only and is NEVER a lookup key. Stamp the verified
        // timestamp
        // ONLY when the IdP attested verification (OIDC email_verified==true, or GitHub's
        // primary+verified
        // /user/emails entry); otherwise it stays null = unverified.
        if (resolvedEmail.verified()) {
            account.setPrimaryEmailVerifiedAt(clock.instant());
        }
        Identity newLink = newIdentity(account, providerId, subject, teamId);
        Account created = accountJitCreator.create(account, newLink);
        log.info(
                "auth.success: resolved accountId={} via provider={} emailVerified={}",
                created.getId(),
                registrationId,
                created.getPrimaryEmailVerifiedAt() != null);
        Account result = promoteIfBootstrapAdmin(created, registrationId, subject, bootstrapLogin);
        if (result.getAppRole() != Account.AppRole.APP_ADMIN && adminBootstrapPolicy.isConfigured()) {
            // Cold-start aid: a new account on an allowlist-configured instance that did NOT
            // match.
            // Logs the provider subject, not the username, so a mis-listed first admin can self-diagnose in one line
            // instead of silently landing as a plain USER with no Admin nav.
            log.info(
                    "auth.bootstrap: new accountId={} did NOT match bootstrap-admins"
                            + " (provider={} subject={}). Add 'provider:subject' to grant APP_ADMIN.",
                    result.getId(),
                    registrationId,
                    subject);
        }
        return new ProvisionResult(result, false);
    }

    /**
     * The token's issuer must be the row's. The registration that validated the token can be up to a minute old on
     * this replica, while the row, which keys the identity, is read now.
     */
    private static void requireIssuerOf(LoginProvider provider, OAuth2User principal) {
        var issuer = principal instanceof OidcUser oidc ? oidc.getIssuer() : null;
        if (issuer == null || !issuer.toString().equals(provider.getBaseUrl())) {
            throw new OAuth2AuthenticationException("invalid_issuer");
        }
    }

    private void publishIdentityLinked(long accountId) {
        eventPublisher.publishEvent(new AccountSecurityChangedEvent(
                accountId, AccountSecurityChangedEvent.Kind.IDENTITY_LINKED, clock.instant()));
    }

    private void requireActiveAccountForWrite(long accountId) {
        // Native identity locks precede account locks, as they do in person erasure. The scalar
        // locked status cannot be replaced by a potentially stale managed Account entity. A write
        // lock also prevents concurrent bootstrap promotions from upgrading two shared locks.
        String status = accountRepository.lockStatusForUpdate(accountId).orElse("");
        if (Account.Status.BLOCKED.name().equals(status)) {
            // The failure handler names this code, so the person learns that an admin blocked the account.
            throw new OAuth2AuthenticationException(ACCOUNT_BLOCKED);
        }
        if (!Account.Status.ACTIVE.name().equals(status)) {
            throw new OAuth2AuthenticationException("identity_processing_suppressed");
        }
    }

    private LoginProvider requireLoginProvider(String registrationId) {
        return loginProviderRepository
                .findByRegistrationId(registrationId)
                .orElseThrow(() -> new IllegalArgumentException("unknown login registrationId: " + registrationId));
    }

    // Bootstrap promotion commits before JWT issuance; demotion requires an explicit admin action.
    private Account promoteIfBootstrapAdmin(
            Account account, String registrationId, String subject, @Nullable String login) {
        if (account.getAppRole() != Account.AppRole.APP_ADMIN
                && adminBootstrapPolicy.shouldPromote(registrationId, subject, login)) {
            boolean hadAdministrator = accountRepository.lockBootstrapAndCheckAdministratorHistory();
            account.setAppRole(Account.AppRole.APP_ADMIN);
            accountRepository.save(account);
            if (hadAdministrator)
                eventPublisher.publishEvent(RoleChangedEvent.instanceAdmin(
                        Objects.requireNonNull(account.getId()), false, true, null, clock.instant()));
            log.info(
                    "auth.bootstrap: promoted accountId={} to APP_ADMIN via bootstrap-admins"
                            + " allowlist (provider={})",
                    account.getId(),
                    registrationId);
        }
        return account;
    }

    private static @Nullable String loginOf(OAuth2User principal) {
        return stringAttr(principal, "login", "preferred_username", "username");
    }

    private Identity newIdentity(Account account, long providerId, String subject, @Nullable String teamId) {
        Identity link = new Identity();
        link.setAccount(account);
        link.setProviderId(providerId);
        link.setProviderUserKey(subject);
        link.setTeamKey(PersonIdentity.teamKey(teamId));
        return link;
    }

    private static String displayName(OAuth2User principal, String subject) {
        String name = stringAttr(principal, "name", "display_name", "login", "preferred_username");
        return (name != null && !name.isBlank()) ? name : "user-" + subject;
    }

    // Slack identifies its tenant by a flat claim or nested team.id; Outline uses flattened team_id.
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
