package de.tum.cit.aet.hephaestus.mentor;

/**
 * Where a {@link ChatThread} is conducted. A thread belongs to exactly one surface for its lifetime: the
 * SSE mentor ({@code WEB}) or a Slack DM ({@code SLACK_DM}). Persisted as a string discriminant on
 * {@code chat_thread.surface}, value-constrained by {@code chk_chat_thread_surface}.
 */
public enum ThreadSurface {
    /**
     * The mentor over an HTTP {@code text/event-stream}: the webapp, and the browser extension's side
     * panel, which sends the same request through its own session. Default for every thread created today.
     */
    WEB,
    /** A Slack direct-message mentor thread, mapped via {@code mentor_slack_thread}. */
    SLACK_DM,
}
