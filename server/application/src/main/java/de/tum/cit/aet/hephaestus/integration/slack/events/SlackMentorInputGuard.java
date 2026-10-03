package de.tum.cit.aet.hephaestus.integration.slack.events;

import org.jspecify.annotations.Nullable;

/**
 * Pre-mentor input guard for inbound Slack DMs. It decides whether a message should run a mentor turn, receive a
 * fixed reply, or be ignored before it reaches the mentor.
 *
 * <p>The default implementation is deliberately small: {@link KeywordSlackMentorInputGuard} only matches a few
 * explicit English phrases. An {@link Action#ALLOW} result means "no guard rule matched", not "the message is safe".
 */
public interface SlackMentorInputGuard {
    /** How the mentor DM flow should handle the message. */
    enum Action {
        /** Run a normal mentor turn. */
        ALLOW,
        /** Send {@link Verdict#responseText()} and do not run a mentor turn. */
        REPLY,
        /** Do not reply and do not run a mentor turn. */
        IGNORE,
    }

    /**
     * @param action how to handle the message
     * @param responseText fixed reply text for {@link Action#REPLY}; {@code null} otherwise
     */
    record Verdict(Action action, @Nullable String responseText) {
        public boolean allowsMentorTurn() {
            return action == Action.ALLOW;
        }
    }

    /** Decide how to handle one inbound DM body. Never returns {@code null}. */
    Verdict decide(String text);
}
