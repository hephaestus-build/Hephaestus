package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.util.Set;

/** Privacy facts that recovery must reapply, and the identifiers needed to replay each fact. */
public enum PrivacyJournalKind {
    ACCOUNT_ERASED("accountId"),
    PERSON_ERASED("identities", "externalCopiesRemoved"),
    WORKSPACE_PURGED("workspaceId"),
    CONNECTION_ERASED("workspaceId", "connectionId"),
    COLLECTION_ERASED("workspaceId", "connectionId", "collectionId"),
    ENTRY_DELETED("workspaceId", "entryId"),
    SLACK_CHANNEL_ERASED("workspaceId", "channelId"),
    SLACK_MESSAGE_CHANGED("workspaceId", "messageId", "change"),
    SLACK_PARTICIPANT_WITHDRAWN("workspaceId", "slackTeamId", "slackUserId", "scope", "decidedAt"),
    REPOSITORY_REMOVED("workspaceId"),
    SCOPE_ERASED("workspaceId", "scopeId"),
    RECORD_DELETED("recordId"),
    GROWTH_DELETED("growthPointId"),
    SEGMENT_ITEM_FORGOTTEN("entryId", "itemId"),
    TRANSFER_REVOKED("transferId"),
    RECORD_KEPT_OUT("recordId", "workspaceId"),
    SPACE_EXCLUDED("accountId", "workspaceId"),
    TRANSFER_RECALLED("transferId"),
    SUPPORT_SHARE_REVOKED("shareId"),
    DRIVER_BINDING_REVOKED("bindingId"),
    WORKSPACE_POLICY_NARROWED("workspaceId", "policy", "value"),
    MEMBERSHIP_ENDED("accountId", "workspaceId"),
    ACCOUNT_SUSPENDED("accountId"),
    SESSION_REVOKED("accountId", "through", "target"),
    CONSENT_WITHDRAWN("accountId", "consentId"),
    RESEARCH_DATASET_RELEASED("datasetId", "workspaceId", "receiptDigest", "releasedAt", "useUntil", "subjectLinks"),
    RESEARCH_REMOVAL_OWED("datasetId", "rowKey", "reason"),
    RESEARCH_REMOVAL_RECORDED("datasetId", "rowKeys", "removedAt"),
    RESEARCH_MAPPING_ERASED("datasetId", "closure", "closedAt"),
    EXPERIMENT_WITHDRAWN("experimentId", "enrolmentId"),
    EXPERIMENT_KEY_DESTROYED("experimentId"),
    TEAM_VERSION_WITHDRAWN("workspaceId", "versionId");

    private final String requiredReferences;

    PrivacyJournalKind(String... requiredReferences) {
        this.requiredReferences = String.join(",", requiredReferences);
    }

    public Set<String> requiredReferences() {
        return Set.of(requiredReferences.split(","));
    }
}
