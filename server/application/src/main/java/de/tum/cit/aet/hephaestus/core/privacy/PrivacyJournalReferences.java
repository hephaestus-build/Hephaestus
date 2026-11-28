package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournalKind;
import de.tum.cit.aet.hephaestus.core.privacy.spi.ResearchSubjectReference;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

final class PrivacyJournalReferences {

    private static final Pattern NATIVE_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,255}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> LOG_TARGET_IDS = Set.of(
            "accountId",
            "bindingId",
            "connectionId",
            "consentId",
            "datasetId",
            "enrolmentId",
            "entryId",
            "experimentId",
            "grantId",
            "growthPointId",
            "itemId",
            "keepSessionId",
            "keepTokenId",
            "messageId",
            "monitorId",
            "recordId",
            "repositoryId",
            "scopeId",
            "sessionId",
            "shareId",
            "tokenId",
            "transferId",
            "versionId",
            "workspaceId");

    private PrivacyJournalReferences() {}

    static void validate(PrivacyJournalKind kind, Map<String, Object> refs) {
        var required = kind.requiredReferences();
        var optional = optional(kind);
        if (!refs.keySet().containsAll(required)
                || (kind == PrivacyJournalKind.SESSION_REVOKED && !validRevocation(refs))
                || (kind == PrivacyJournalKind.REPOSITORY_REMOVED && !validRepositoryRemoval(refs))
                || refs.entrySet().stream()
                        .anyMatch(field -> (!required.contains(field.getKey()) && !optional.contains(field.getKey()))
                                || !valid(field.getKey(), field.getValue()))
                || (kind == PrivacyJournalKind.PERSON_ERASED
                        && !refs.containsKey("accountId")
                        && refs.get("identities") instanceof List<?> identities
                        && identities.isEmpty())) {
            throw new IllegalArgumentException("Invalid recovery references for " + kind);
        }
    }

    /** Only top-level internal identifiers belong in the recovery log. */
    static Map<String, Object> logTargetIds(Map<String, Object> validated) {
        Map<String, Object> ids = new HashMap<>();
        validated.forEach((name, value) -> {
            if (LOG_TARGET_IDS.contains(name) && (value instanceof UUID || positiveLong(value))) {
                ids.put(name, value);
            }
        });
        return Map.copyOf(ids);
    }

    private static Set<String> optional(PrivacyJournalKind kind) {
        return switch (kind) {
            case PERSON_ERASED -> Set.of("accountId");
            case SLACK_CHANNEL_ERASED -> Set.of("erasedAt", "copiesErasedThrough");
            case SESSION_REVOKED -> Set.of("keepTokenId", "keepSessionId", "sessionId", "tokenId", "grantId");
            case REPOSITORY_REMOVED -> Set.of("monitorId", "repositoryId", "itemId");
            default -> Set.of();
        };
    }

    private static boolean validRepositoryRemoval(Map<String, Object> refs) {
        if (refs.containsKey("repositoryId")) return !refs.containsKey("monitorId");
        return refs.containsKey("monitorId") && !refs.containsKey("itemId");
    }

    private static boolean validRevocation(Map<String, Object> refs) {
        if (!(refs.get("target") instanceof String target)) return false;
        return switch (target) {
            case "ACCOUNT" ->
                refs.size() == 3
                        || (refs.size() == 4 && (refs.containsKey("keepTokenId") || refs.containsKey("keepSessionId")));
            case "SESSION" -> refs.size() == 4 && refs.containsKey("sessionId");
            case "TOKEN" -> refs.size() == 4 && refs.containsKey("tokenId");
            case "MCP_GRANT" -> refs.size() == 4 && refs.containsKey("grantId");
            default -> false;
        };
    }

    private static boolean valid(String name, Object value) {
        return switch (name) {
            case "through",
                    "releasedAt",
                    "useUntil",
                    "decidedAt",
                    "removedAt",
                    "closedAt",
                    "erasedAt",
                    "copiesErasedThrough" -> value instanceof Instant;
            case "scope" -> "MONITORED_CHANNELS".equals(value);
            case "target" -> value instanceof String;
            case "receiptDigest" ->
                value instanceof String digest && SHA256.matcher(digest).matches();
            case "rowKeys" ->
                value instanceof List<?> keys
                        && !keys.isEmpty()
                        && keys.stream()
                                .allMatch(key -> key instanceof String id
                                        && NATIVE_ID.matcher(id).matches());
            case "subjectLinks" ->
                value instanceof List<?> subjects
                        && subjects.stream().allMatch(ResearchSubjectReference.class::isInstance);
            case "reason" ->
                value instanceof String reason
                        && switch (reason) {
                            case "CONSENT_WITHDRAWN", "CONSENT_SUPERSEDED", "QUESTION_CHANGED", "ACCOUNT_ERASED" ->
                                true;
                            default -> false;
                        };
            case "closure" -> "DESTROYED".equals(value) || "ANONYMIZED".equals(value);
            case "change" -> "EDITED".equals(value) || "DELETED".equals(value);
            case "policy" -> "export".equals(value) || "inbound".equals(value);
            case "collectionId", "channelId", "rowKey", "value", "slackTeamId", "slackUserId" ->
                value instanceof String id && NATIVE_ID.matcher(id).matches();
            case "workspaceId", "accountId", "connectionId", "messageId", "repositoryId", "monitorId", "consentId" ->
                positiveLong(value);
            case "entryId", "keepTokenId", "keepSessionId", "sessionId", "tokenId", "grantId", "versionId" ->
                value instanceof UUID;
            case "externalCopiesRemoved" -> value instanceof Boolean;
            case "identities" ->
                value instanceof List<?> entries
                        && entries.stream().allMatch(PersonDataRecovery.NativeIdentity.class::isInstance);
            default -> value instanceof UUID || positiveLong(value);
        };
    }

    private static boolean positiveLong(Object value) {
        return (value instanceof Long id && id > 0) || (value instanceof Integer number && number > 0);
    }
}
