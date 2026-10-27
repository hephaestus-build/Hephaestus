package de.tum.cit.aet.hephaestus.integration.scm.feedback;

import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackNotSentException;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryContent;

/**
 * The text Hephaestus posts on the work: plain Markdown that mentions no one. Every write and every readback
 * comparison uses it, so a copy is recognized by the bytes it was posted with. Applying it to its own output changes
 * nothing.
 */
public final class PostedText {

    private PostedText() {}

    /**
     * The authored text as it is posted.
     *
     * @throws FeedbackNotSentException if nothing safe to post remains; nothing was sent
     */
    public static String of(String authored) {
        String posted;
        try {
            posted = ScmFeedbackFormatter.sanitize(authored);
        } catch (FeedbackNotSentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new FeedbackNotSentException("Feedback text could not be made safe to post", e);
        }
        if (posted.isBlank() && !authored.isBlank())
            throw new FeedbackNotSentException("Feedback text is empty once it is made safe to post");
        return posted;
    }

    /** A summary as it is posted: its text made safe, then the hidden marker that finds it again. */
    public static String summary(SummaryContent content) {
        String marker = content.writeMarker();
        if (marker == null || marker.isBlank()) return of(content.body());
        return of(content.body().replace(marker, "").stripTrailing()).stripTrailing() + "\n\n" + marker;
    }
}
