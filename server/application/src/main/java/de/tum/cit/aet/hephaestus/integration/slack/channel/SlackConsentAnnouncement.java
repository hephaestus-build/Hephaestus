package de.tum.cit.aet.hephaestus.integration.slack.channel;

import java.util.UUID;

/** The payload of a {@code SLACK_CONSENT} action: the public announcement that enables ingestion once confirmed. */
public record SlackConsentAnnouncement(
        String channelId,
        String teamId,
        long channelRowId,
        long requestId,
        UUID marker,
        String oldestTs,
        boolean retry) {

    private static final String KEY_PREFIX = "slack_consent:";

    public static String idempotencyKey(long workspaceId, long requestId) {
        return KEY_PREFIX + workspaceId + ":" + requestId;
    }

    public static long requestId(String idempotencyKey) {
        return Long.parseLong(idempotencyKey.substring(idempotencyKey.lastIndexOf(':') + 1));
    }
}
