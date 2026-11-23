package de.tum.cit.aet.hephaestus.core.privacy.spi;

/**
 * The kinds of privacy fact that a producer records. Each kind removes data or ends a grant, so replaying a fact after a
 * database restore can only narrow access. A change of a setting, a mute or a disagreement is not a kind. The first
 * kinds are the erasures that the erasure recovery manifest lists, which {@code PersonDataRecovery} reapplies.
 */
public enum PrivacyJournalKind {
    /** A person's account was erased. */
    ACCOUNT_ERASED,
    /** A workspace was purged. */
    WORKSPACE_PURGED,
    /** An admin erased a connection's provider data from a workspace. */
    CONNECTION_ERASED,
    /** An admin erased a documentation collection from a workspace's mirror. */
    COLLECTION_ERASED,
    /** A developer deleted their own conversation message. */
    ENTRY_DELETED,
    /** A Slack channel was revoked by an admin or deleted in Slack. */
    SLACK_CHANNEL_ERASED,
    /** The author of a stored Slack message edited or deleted it in Slack. */
    SLACK_MESSAGE_CHANGED,
}
