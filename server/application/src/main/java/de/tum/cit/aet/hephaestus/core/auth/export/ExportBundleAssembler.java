package de.tum.cit.aet.hephaestus.core.auth.export;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.AccountService;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountFeatureRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountPreferencesQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Maps account-owned data to the explicit field allowlist in {@link ExportBundle}. */
@ConditionalOnServerRole
@Component
@WorkspaceAgnostic("GDPR export aggregates an account's own data across workspaces; not workspace-scoped")
public class ExportBundleAssembler {

    /** Auth-event export window. */
    private static final int AUTH_EVENT_WINDOW_MONTHS = 12;

    private final AccountService accountService;
    private final AccountFeatureRepository accountFeatureRepository;
    private final AuthEventRepository authEventRepository;
    private final AccountWorkspaceMembershipQuery workspaceMembershipQuery;
    private final AccountPreferencesQuery preferencesQuery;
    private final GitProviderRegistry gitProviderRegistry;
    private final Clock clock;

    public ExportBundleAssembler(
            AccountService accountService,
            AccountFeatureRepository accountFeatureRepository,
            AuthEventRepository authEventRepository,
            AccountWorkspaceMembershipQuery workspaceMembershipQuery,
            AccountPreferencesQuery preferencesQuery,
            GitProviderRegistry gitProviderRegistry,
            Clock clock) {
        this.accountService = accountService;
        this.accountFeatureRepository = accountFeatureRepository;
        this.authEventRepository = authEventRepository;
        this.workspaceMembershipQuery = workspaceMembershipQuery;
        this.preferencesQuery = preferencesQuery;
        this.gitProviderRegistry = gitProviderRegistry;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ExportBundle assemble(Long accountId) {
        Account account = accountService.requireById(accountId);
        List<IdentityLink> identities = accountService.activeIdentities(accountId);

        ExportBundle.Profile profile = new ExportBundle.Profile(
                Objects.requireNonNull(account.getId()),
                account.getDisplayName(),
                account.getPrimaryEmail(),
                // appRole deliberately not disclosed here — see ExportBundle.Profile (Art. 20(1) scope).
                account.getStatus().name(),
                Objects.requireNonNull(account.getCreatedAt()));

        List<ExportBundle.Identity> identityViews =
                identities.stream().map(this::toIdentity).toList();

        List<ExportBundle.WorkspaceMembership> memberships =
                workspaceMembershipQuery.membershipsForAccount(accountId).stream()
                        .map(m -> new ExportBundle.WorkspaceMembership(m.workspaceSlug(), m.workspaceName(), m.role()))
                        .toList();

        List<String> featureFlags = accountFeatureRepository.findFlagsByAccountId(accountId);

        ExportBundle.Preferences preferences = preferencesQuery
                .preferencesForAccount(accountId)
                .map(p -> new ExportBundle.Preferences(p.participateInResearch(), p.practiceFeedbackDeliveryEnabled()))
                .orElse(null);

        // Real calendar months (not 30-day approximations) so this window matches the partition
        // retention (pg_partman, 12 months), which is also 12 calendar months.
        Instant since = Instant.now(clock)
                .atZone(ZoneOffset.UTC)
                .minusMonths(AUTH_EVENT_WINDOW_MONTHS)
                .toInstant();
        List<ExportBundle.AuthEvent> authEvents = authEventRepository.findByAccountSince(accountId, since).stream()
                // Operator-authored impersonation records remain in the audit log, not the portable bundle.
                .filter(e -> !isImpersonationEvent(e))
                .map(ExportBundleAssembler::toAuthEvent)
                .toList();

        return new ExportBundle(
                ExportBundle.SCHEMA_VERSION,
                Instant.now(clock),
                profile,
                identityViews,
                memberships,
                featureFlags,
                preferences,
                authEvents);
    }

    private ExportBundle.Identity toIdentity(IdentityLink il) {
        String provider = gitProviderRegistry.providerTypeName(il.getProviderId());
        return new ExportBundle.Identity(
                provider,
                il.getSubject(),
                il.getUsernameAtSignup(),
                il.getEmailAtSignup(),
                il.getDisplayName(),
                il.getLinkedAt(),
                il.getLastLoginAt());
    }

    private static ExportBundle.AuthEvent toAuthEvent(AuthEvent e) {
        // Exclude acting-account ids and details: they can contain another account's data.
        return new ExportBundle.AuthEvent(
                e.getId() != null ? e.getId().getOccurredAt() : null,
                e.getEventType() != null ? e.getEventType().name() : null,
                e.getResult() != null ? e.getResult().name() : null,
                e.getIpInet(),
                e.getUserAgent());
    }

    /** Impersonation events are operator-authored records about the subject; excluded under Art. 20(4). */
    private static boolean isImpersonationEvent(AuthEvent e) {
        AuthEvent.EventType t = e.getEventType();
        return (t == AuthEvent.EventType.IMPERSONATION_BEGIN || t == AuthEvent.EventType.IMPERSONATION_END);
    }
}
