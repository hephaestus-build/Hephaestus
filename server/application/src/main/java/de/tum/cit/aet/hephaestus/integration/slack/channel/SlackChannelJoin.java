package de.tum.cit.aet.hephaestus.integration.slack.channel;

/** The payload of a {@code SLACK_JOIN} action: the bot joins one public channel. */
public record SlackChannelJoin(String channelId, String teamId, long channelRowId, long setupRevision) {}
