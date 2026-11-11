package de.tum.cit.aet.hephaestus.core.auth.export;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.AccountService;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountFeatureRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountAiChoiceExport;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountConversationExport;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountFeedbackExport;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountPreferencesQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.auth.spi.NotificationPreferencesExportQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.ResearchParticipationQuery;
import de.tum.cit.aet.hephaestus.core.auth.webauthn.PasskeyCredentialRepository;
import de.tum.cit.aet.hephaestus.core.auth.webauthn.PasskeyJson;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Maps account-owned data to the explicit field allowlist in {@link ExportBundle}. */
@ConditionalOnServerRole
@Component
@WorkspaceAgnostic("GDPR export aggregates an account's own data across workspaces; not workspace-scoped")
public class ExportBundleAssembler {

    /** Auth-event export window. */
    private static final int AUTH_EVENT_WINDOW_MONTHS = 12;

    private final PasskeyCredentialRepository passkeys;
    private final PasskeyJson passkeyJson;
    private final AccountService accountService;
    private final AccountFeatureRepository accountFeatureRepository;
    private final AuthEventRepository authEventRepository;
    private final AccountMembershipQuery workspaceMembershipQuery;
    private final AccountPreferencesQuery preferencesQuery;
    private final GitProviderRegistry gitProviderRegistry;
    private final Clock clock;
    private final AccountAiChoiceExport aiChoiceExport;
    private final ResearchParticipationQuery researchParticipation;
    private final NotificationPreferencesExportQuery notificationPreferences;
    private final AccountConversationExport conversations;
    private final AccountFeedbackExport feedback;

    public ExportBundleAssembler(
            AccountService accountService,
            AccountFeatureRepository accountFeatureRepository,
            AuthEventRepository authEventRepository,
            AccountMembershipQuery workspaceMembershipQuery,
            AccountPreferencesQuery preferencesQuery,
            GitProviderRegistry gitProviderRegistry,
            Clock clock,
            AccountAiChoiceExport aiChoiceExport,
            NotificationPreferencesExportQuery notificationPreferences,
            ResearchParticipationQuery researchParticipation,
            PasskeyCredentialRepository passkeys,
            PasskeyJson passkeyJson,
            AccountConversationExport conversations,
            AccountFeedbackExport feedback) {
        this.conversations = conversations;
        this.feedback = feedback;
        this.passkeys = passkeys;
        this.passkeyJson = passkeyJson;
        this.accountService = accountService;
        this.accountFeatureRepository = accountFeatureRepository;
        this.authEventRepository = authEventRepository;
        this.workspaceMembershipQuery = workspaceMembershipQuery;
        this.preferencesQuery = preferencesQuery;
        this.gitProviderRegistry = gitProviderRegistry;
        this.clock = clock;
        this.aiChoiceExport = aiChoiceExport;
        this.notificationPreferences = notificationPreferences;
        this.researchParticipation = researchParticipation;
    }

    @Transactional(readOnly = true)
    public ExportBundle assemble(Long accountId) {
        Account account = accountService.requireById(accountId);
        List<Identity> identities = accountService.activeIdentities(accountId);
        var profiles = gitProviderRegistry.profiles(
                identities.stream().map(ExportBundleAssembler::key).toList());

        ExportBundle.Profile profile = new ExportBundle.Profile(
                Long.toString(Objects.requireNonNull(account.getId())),
                account.getDisplayName(),
                account.getPrimaryEmail(),
                account.getTimeZone(),
                // appRole deliberately not disclosed here — see ExportBundle.Profile (Art.
                // 20(1) scope).
                account.getStatus().name(),
                Objects.requireNonNull(account.getCreatedAt()),
                account.isPasskeyProtectionEnabled());

        List<ExportBundle.Identity> identityViews = identities.stream()
                .map(identity -> toIdentity(identity, profiles.get(key(identity))))
                .toList();

        List<ExportBundle.Membership> memberships = workspaceMembershipQuery.membershipsForAccount(accountId).stream()
                .map(m -> new ExportBundle.Membership(
                        m.workspaceSlug(), m.workspaceName(), m.role().name()))
                .toList();

        List<String> featureFlags = accountFeatureRepository.findFlagsByAccountId(accountId);

        boolean practiceFeedbackDelivery = preferencesQuery
                .preferencesForAccount(accountId)
                .map(AccountPreferencesQuery.PreferencesView::practiceFeedbackDeliveryEnabled)
                .orElse(AccountPreferencesQuery.PreferencesView.PRACTICE_FEEDBACK_DELIVERY_ENABLED_BY_DEFAULT);
        ExportBundle.Preferences preferences = new ExportBundle.Preferences(
                researchParticipation.participates(accountId),
                practiceFeedbackDelivery,
                account.isPublicActivityVisible());

        // Real calendar months (not 30-day approximations) so this window matches the partition
        // retention (pg_partman, 12 months), which is also 12 calendar months.
        Instant since = Instant.now(clock)
                .atZone(ZoneOffset.UTC)
                .minusMonths(AUTH_EVENT_WINDOW_MONTHS)
                .toInstant();
        List<ExportBundle.AuthEvent> authEvents = authEventRepository.findByAccountSince(accountId, since).stream()
                // Operator-authored impersonation records remain in the audit log, not the
                // portable bundle.
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
                aiChoiceExport.choice(accountId),
                notificationPreferences.preferences(accountId),
                passkeys.findByAccountId(accountId).stream()
                        .map(p -> {
                            var credential = passkeyJson.readCredential(p.getRecordJson());
                            return new ExportBundle.Passkey(
                                    credential.getLabel(), credential.getCreated(), credential.getLastUsed());
                        })
                        .toList(),
                conversations.messages(accountId),
                feedback.received(accountId),
                feedback.responses(accountId));
    }

    private static GitProviderRegistry.IdentityKey key(Identity identity) {
        return new GitProviderRegistry.IdentityKey(
                identity.getProviderId(), identity.getProviderUserKey(), identity.getTeamKey());
    }

    private ExportBundle.Identity toIdentity(Identity il, GitProviderRegistry.@Nullable IdentityProfile profile) {
        String provider = gitProviderRegistry.providerTypeName(il.getProviderId());
        return new ExportBundle.Identity(
                provider,
                il.getProviderUserKey(),
                profile == null ? null : profile.login(),
                profile == null ? null : profile.displayName(),
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

    // Operator-authored impersonation events are excluded from export under Art. 20(4).
    private static boolean isImpersonationEvent(AuthEvent e) {
        AuthEvent.EventType t = e.getEventType();
        return (t == AuthEvent.EventType.IMPERSONATION_BEGIN || t == AuthEvent.EventType.IMPERSONATION_END);
    }
}
