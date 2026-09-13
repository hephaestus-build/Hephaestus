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
import de.tum.cit.aet.hephaestus.core.auth.spi.NotificationPreferencesExportQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles the {@link ExportBundle} for one account by aggregating data the principal owns from
 * the owning sources:
 *
 * <ol>
 *   <li><b>account profile</b> + <b>own identity links</b> — {@link AccountService} ({@code core.auth} domain)</li>
 *   <li><b>feature flags</b> — {@link AccountFeatureRepository} ({@code core.auth} domain)</li>
 *   <li><b>auth events (last 12 months)</b> — {@link AuthEventRepository} ({@code core.auth} audit)</li>
 *   <li><b>workspace memberships</b> — {@link AccountWorkspaceMembershipQuery} (auth-spi → {@code workspace})</li>
 *   <li><b>account preferences</b> — {@link AccountPreferencesQuery} (auth-spi → {@code account})</li>
 *   <li><b>email subscriptions</b> — {@link NotificationPreferencesExportQuery} (auth-spi → notification)</li>
 * </ol>
 *
 * <p>The two cross-module sources are reached only through the {@code core.auth.spi} named
 * interface (implemented in {@code workspace} / {@code account}); this module never imports those
 * modules' domain types. The {@code Account → login} bridge ({@code IdentityLink.usernameAtSignup})
 * is owned here and fed to the workspace/preferences queries.
 *
 * <h2>Disclosure discipline</h2>
 * Only the fields enumerated in {@link ExportBundle} are emitted. Tokens, encrypted credential
 * blobs, JWT signing keys, password-equivalents (there are none in this system), and any other
 * account's data are structurally excluded — there is no code path here that reads them.
 */
@ConditionalOnServerRole
@Component
@WorkspaceAgnostic("GDPR export aggregates an account's own data across workspaces; not workspace-scoped")
public class ExportBundleAssembler {

    /** GDPR Art. 20 auth-event window. */
    private static final int AUTH_EVENT_WINDOW_MONTHS = 12;

    private final AccountService accountService;
    private final AccountFeatureRepository accountFeatureRepository;
    private final AuthEventRepository authEventRepository;
    private final AccountWorkspaceMembershipQuery workspaceMembershipQuery;
    private final AccountPreferencesQuery preferencesQuery;
    private final GitProviderRegistry gitProviderRegistry;
    private final Clock clock;
    private final NotificationPreferencesExportQuery notificationPreferences;

    public ExportBundleAssembler(
            AccountService accountService,
            AccountFeatureRepository accountFeatureRepository,
            AuthEventRepository authEventRepository,
            AccountWorkspaceMembershipQuery workspaceMembershipQuery,
            AccountPreferencesQuery preferencesQuery,
            GitProviderRegistry gitProviderRegistry,
            Clock clock,
            NotificationPreferencesExportQuery notificationPreferences) {
        this.accountService = accountService;
        this.accountFeatureRepository = accountFeatureRepository;
        this.authEventRepository = authEventRepository;
        this.workspaceMembershipQuery = workspaceMembershipQuery;
        this.preferencesQuery = preferencesQuery;
        this.gitProviderRegistry = gitProviderRegistry;
        this.clock = clock;
        this.notificationPreferences = notificationPreferences;
    }

    @Transactional(readOnly = true)
    public ExportBundle assemble(Long accountId) {
        Account account = accountService.requireById(accountId);
        List<IdentityLink> identities = accountService.activeIdentities(accountId);

        Set<String> logins = new LinkedHashSet<>();
        for (IdentityLink link : identities) {
            if (link.getUsernameAtSignup() != null
                    && !link.getUsernameAtSignup().isBlank()) {
                logins.add(link.getUsernameAtSignup());
            }
        }

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
                workspaceMembershipQuery.membershipsForLogins(logins).stream()
                        .map(m -> new ExportBundle.WorkspaceMembership(m.workspaceSlug(), m.workspaceName(), m.role()))
                        .toList();

        List<String> featureFlags = accountFeatureRepository.findFlagsByAccountId(accountId);

        // Preferences are keyed by a single SCM login; use the principal's primary (first active)
        // login. Absent if no preferences row exists yet.
        ExportBundle.Preferences preferences = logins.stream()
                .findFirst()
                .flatMap(preferencesQuery::preferencesForLogin)
                .map(p -> new ExportBundle.Preferences(p.participateInResearch(), p.practiceFeedbackDeliveryEnabled()))
                .orElse(null);

        // Real calendar months (not 30-day approximations) so this window matches the partition
        // retention (pg_partman, 12 months), which is also 12 calendar months.
        Instant since = Instant.now(clock)
                .atZone(ZoneOffset.UTC)
                .minusMonths(AUTH_EVENT_WINDOW_MONTHS)
                .toInstant();
        List<ExportBundle.AuthEvent> authEvents = authEventRepository.findByAccountSince(accountId, since).stream()
                // GDPR Art. 20(4): the export "shall not adversely affect the rights and freedoms of
                // others." Impersonation rows are authored about this subject BY ANOTHER account (the
                // operator) and carry that operator's id (acting_account_id) + operator-supplied reason
                // (details) — operator-accountability audit records, not data this subject provided
                // (Art. 20(1)). Excluded from the portable bundle; they remain in the immutable auth_event log.
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
                authEvents,
                notificationPreferences.preferences(accountId));
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
        // NOTE (Art. 20(4) chokepoint): this mapper deliberately never reads e.getActingAccountId()
        // or e.getDetails() — both can reference / be authored by another account. Do NOT add them.
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
