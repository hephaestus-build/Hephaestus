package de.tum.cit.aet.hephaestus.practices.feedback;

/**
 * The destination-class axis of a {@link Feedback} unit: <em>where</em> it lands, decoupled from <em>what we say</em>,
 * so the same synthesised unit can be routed to different rendering surfaces.
 *
 * <p>Every channel is <b>developer-facing</b>: a unit is delivered to its recipient developer
 * ({@link Feedback#getRecipientUserId()}), never to a mentor, instructor, or grader. The system has no
 * facilitator/evaluative delivery channel.
 *
 * <p><b>All three names sit on one axis: where the unit lands.</b> Not what the developer is supposed to
 * do with it, not what cognitive level it addresses. The one question a reader raises here is that the
 * mentor also renders inside the app, so read the two private values this way:
 *
 * <ul>
 *   <li>{@link #IN_CHAT} is the <b>dialogic</b> channel — a turn in a conversation, wherever that
 *       conversation runs: the in-app mentor at {@code /w/:slug/mentor}, or Slack. The surface it renders
 *       on is not what makes it {@code IN_CHAT}; being a turn is.</li>
 *   <li>{@link #IN_APP} is the <b>non-dialogic</b> practice surface — the developer's own practice pages.
 *       Nobody replies to it.</li>
 * </ul>
 *
 * <p>Ask <em>is it a turn?</em> before <em>which screen?</em>, and the two never overlap.
 *
 * <p>Constrained at the DB by {@code chk_feedback_channel}. The three values happen to line up with the
 * task, self-regulation and process levels of Hattie &amp; Timperley's model (ADR 0029), but they are not
 * named for it: a channel is a destination, and a level is a claim about content that the destination
 * does not enforce.
 *
 * <p>This is the destination a delivery is recorded against, not the thing that does the delivering:
 * the vendor pipes are {@code integration.core.spi.SummaryChannel}, {@code InlineFeedbackChannel} and
 * {@code ApprovalChannel}, each named for what it posts. Both in-context lanes collapse to
 * {@link #IN_CONTEXT} here, because the ledger records that the developer was told in place while the
 * delivery path still has to know which bean to call.
 */
public enum FeedbackChannel {
    /** Placed directly on the work artifact (PR summary / inline note, issue comment). */
    IN_CONTEXT,
    /** A turn in an ongoing mentor conversation with the recipient — the in-app mentor, or Slack. */
    IN_CHAT,
    /** Aggregated onto the recipient's own practice pages inside Hephaestus. Nobody replies to it. */
    IN_APP;

    /**
     * Whether feedback on this channel is pushed out of Hephaestus — a note on the work, public to everyone
     * who reads the pull request and reversible only by deleting it. A practice page or a mentor turn is
     * pulled: the developer reads it on request, and nobody else sees it. Human approval exists for what is
     * pushed ({@link de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy#delivers}).
     */
    public boolean pushed() {
        return this == IN_CONTEXT;
    }
}
