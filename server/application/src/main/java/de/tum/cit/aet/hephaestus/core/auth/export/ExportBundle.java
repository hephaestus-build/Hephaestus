package de.tum.cit.aet.hephaestus.core.auth.export;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountAiChoiceExport;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountConversationExport;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountFeedbackExport;
import de.tum.cit.aet.hephaestus.core.auth.spi.NotificationPreferencesExportQuery;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The structured GDPR Art. 20 export document. Serialized to JSON as the {@link AccountExport}
 * payload. Top-level {@code generatedAt} + {@code schemaVersion} bracket the contents so the format
 * can evolve.
 *
 * <p>This type is the explicit contract for what we disclose. It deliberately has NO field for
 * tokens, credential blobs, signing keys, password-equivalents, or other users' data — the absence
 * is the control. Adding such a field here is the only place it could leak, so this record is the
 * review chokepoint.
 */
public record ExportBundle(
        String schemaVersion,
        Instant generatedAt,
        Profile account,
        List<Identity> identities,
        List<Membership> workspaceMemberships,
        List<String> featureFlags,
        @Nullable Preferences preferences,
        List<AuthEvent> authEvents,
        AccountAiChoiceExport.@Nullable Choice aiChoice,
        NotificationPreferencesExportQuery.@Nullable Preferences notificationPreferences,
        List<Passkey> passkeys,
        List<AccountConversationExport.Message> conversationMessages,
        List<AccountFeedbackExport.Received> feedbackReceived,
        List<AccountFeedbackExport.Response> feedbackResponses) {
    /** Current export schema version: the major part changes on a breaking shape change, the minor on a new field. */
    public static final String SCHEMA_VERSION = "2.2";

    public record Passkey(
            String label,
            @Nullable Instant createdAt,
            @Nullable Instant lastUsedAt) {}

    public record Profile(
            String id,
            String displayName,
            @Nullable String primaryEmail,
            @Nullable String timeZone,
            // appRole intentionally omitted: it is controller-assigned authorization state, NOT
            // data the
            // subject "provided" under GDPR Art. 20(1) — out of portability scope. (Admin views
            // surface it
            // elsewhere.) `status` is kept: it is the subject's own account-lifecycle state.
            String status,
            Instant createdAt,
            boolean passkeyProtectionEnabled) {}

    public record Identity(
            String provider,
            String subject,
            @Nullable String login,
            @Nullable String displayName,
            Instant linkedAt,
            @Nullable Instant lastLoginAt) {}

    public record Membership(
            @Nullable String slug,
            @Nullable String name,
            @Nullable String role) {}

    public record Preferences(
            boolean participateInResearch, boolean practiceFeedbackDeliveryEnabled, boolean publicActivityVisible) {}

    public record AuthEvent(
            @Nullable Instant occurredAt,
            @Nullable String eventType,
            @Nullable String result,
            @Nullable String ip,
            @Nullable String userAgent) {}
}
