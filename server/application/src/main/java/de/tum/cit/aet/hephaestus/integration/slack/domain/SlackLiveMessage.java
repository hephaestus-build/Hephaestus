package de.tum.cit.aet.hephaestus.integration.slack.domain;

import org.jspecify.annotations.Nullable;

/** The key of a stored message that is not tombstoned: enough to compare it with what Slack still returns. */
public record SlackLiveMessage(String slackTs, @Nullable String slackThreadTs) {

    /** The {@code ts} of its thread's root, which is its own {@code ts} for a message that is not a reply. */
    public String rootTs() {
        return slackThreadTs == null ? slackTs : slackThreadTs;
    }

    public boolean isReply() {
        return !slackTs.equals(rootTs());
    }
}
