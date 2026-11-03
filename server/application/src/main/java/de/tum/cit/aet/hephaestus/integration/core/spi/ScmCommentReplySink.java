package de.tum.cit.aet.hephaestus.integration.core.spi;

/**
 * Answers a person's request comment in its own thread, when no feedback answers it: the review kept every result, or
 * the request was refused. One implementation for each SCM kind that publishes request comments.
 */
public interface ScmCommentReplySink {
    IntegrationKind kind();

    /**
     * Posts {@code text} as plain text, with a link to the work's feedback in Hephaestus. Best effort: a failure is
     * logged and never fails the caller.
     *
     * @param replyAddress the address that the adapter put on the request comment
     */
    void reply(long workspaceId, long itemId, String replyAddress, String text);
}
