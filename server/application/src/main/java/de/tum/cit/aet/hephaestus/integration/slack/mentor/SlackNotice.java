package de.tum.cit.aet.hephaestus.integration.slack.mentor;

import org.jspecify.annotations.Nullable;

/** The payload of a {@code SLACK_NOTICE} action: a direct message that has no conversation entry. */
public record SlackNotice(
        String channelId, @Nullable String threadTs, String messageTs, String text, boolean linkAccount) {}
