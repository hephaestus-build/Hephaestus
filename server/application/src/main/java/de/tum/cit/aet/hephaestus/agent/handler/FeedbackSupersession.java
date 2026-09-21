package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Retires the message a newer one replaces, on the two lanes where "queued" and "read" are different
 * states.
 *
 * <p><b>The rule this exists to hold: nothing that has been received may be un-said.</b> A mentor unit
 * becomes DELIVERED at the moment the developer actually reads it, so replacing one that has already
 * flipped would rewrite something a person has in their head; {@link #supersede} claims only what is
 * still queued. The in-app lane keeps the ledger and not the page: an open card about a habit is replaced
 * by the newer card about it, read or not, because the practice page is a list of habits to work on and
 * two open cards about one habit are one habit said twice — {@link #replaceOpen}, which retires exactly
 * the card the caller found open. What is never replaced is a card that is closed: resolved by the work
 * or by the developer, or closed because the practice changed, it stays as the record of what was said,
 * and only the caller can tell, so only the caller decides. The in-context lane is not this act and does
 * not come through here — there the comment is edited in place on the merge request, so retiring the
 * delivered row is what makes the ledger agree with what is now visible, and {@link FeedbackLedgerRecorder}
 * keeps doing it.
 *
 * <p><b>The caller must write the replacement in the same transaction as the claim.</b> This is one half
 * of a swap: on its own it takes a message out of somebody's queue and puts nothing back. The two must
 * commit or roll back together, or a lost write leaves the recipient with strictly less than they had.
 */
@Component
public class FeedbackSupersession {

    private static final Logger log = LoggerFactory.getLogger(FeedbackSupersession.class);

    /**
     * How many times a claim will re-aim at a thread that moved under it.
     *
     * <p>A run that loses the claim has learnt something: the run that beat it has queued a card of its
     * own, and <em>that</em> is the live statement of the habit now. Aiming at it on the next pass is what
     * keeps one habit to one live card; walking away instead would leave two, which is the pile
     * supersession exists to prevent. Each pass either wins or discovers a target it did not know about,
     * so the ceiling is a backstop rather than a policy — more runs than this converging on one person's
     * one habit at one instant is a stuck producer, not a race.
     */
    private static final int MAX_ATTEMPTS = 3;

    private final FeedbackRepository feedbackRepository;

    FeedbackSupersession(FeedbackRepository feedbackRepository) {
        this.feedbackRepository = feedbackRepository;
    }

    /**
     * What became of an attempt to replace the message queued on a thread, and what the replacement should
     * therefore record.
     *
     * @param replacesId the row the new unit points back at, or {@code null} when it follows nothing
     */
    public record Outcome(Disposition disposition, @Nullable UUID replacesId) {
        /** Nothing was claimed and nothing is followed — the unit stands on its own. */
        public static Outcome standalone() {
            return new Outcome(Disposition.NEW, null);
        }

        /** Whether this call took a queued message out of the recipient's queue. */
        public boolean retiredSomething() {
            return disposition == Disposition.SUPERSEDED;
        }
    }

    /** The three things a supersession attempt can turn out to be. */
    public enum Disposition {
        /** The queued message was retired; the new unit takes its place. */
        SUPERSEDED,
        /**
         * The recipient read the queued message before this run got to it. It keeps its DELIVERED state
         * and the new unit is written anyway, pointing back at it: the thread continues rather than being
         * rewritten. Only {@link #supersede} answers this; the in-app lane's {@link #replaceOpen} retires a
         * read card too.
         */
        CONTINUED,
        /** There was nothing live to follow — no thread, or another run already moved it on. */
        NEW,
    }

    /**
     * Claim the message queued on one thread so a newer one can replace it.
     *
     * <p>Never throws on a target it could not claim, because failing to claim one is not an error. The
     * queued message may have been read a second before this ran, another run may have replaced it first,
     * or it may never have existed — all three are ordinary, and all three end with the new unit still
     * being written. The reason it can be written regardless is that the compare-and-set is not what stops
     * a developer being told the same thing twice; the composer reading what has already been said, and
     * the lane's resurface cooldown, are. Dropping the unit here would answer a delivery race with silence
     * about something that was measured.
     *
     * @param channel the lane the thread lives on; a key from another lane must not match
     * @param threadKey the continuity key of the thread being replaced
     */
    public Outcome supersede(long workspaceId, long recipientUserId, FeedbackChannel channel, String threadKey) {
        UUID lastRefused = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Optional<UUID> target =
                    feedbackRepository.findLatestOnThread(workspaceId, recipientUserId, channel.name(), threadKey);
            if (target.isEmpty()) {
                return Outcome.standalone();
            }
            UUID targetId = target.get();
            if (targetId.equals(lastRefused)) {
                // The head of the thread did not move, so it is settled in a state nothing can claim —
                // suppressed, or failed. Another pass would ask the same question and get the same answer.
                break;
            }
            if (feedbackRepository.markSuperseded(workspaceId, targetId) == 1) {
                return new Outcome(Disposition.SUPERSEDED, targetId);
            }
            // Read between the moment the composer was shown the queue and now. The row keeps DELIVERED
            // and the replacement still records what it follows, so the thread reads as one conversation
            // over time rather than two unrelated messages that happen to share a key.
            if (feedbackRepository.isDelivered(workspaceId, targetId)) {
                log.info(
                        "Supersession target was read first; continuing the thread instead: channel={}, target={}",
                        channel,
                        targetId);
                return new Outcome(Disposition.CONTINUED, targetId);
            }
            lastRefused = targetId;
        }
        // Nothing left to claim. Pointing back at a row another run has already claimed would put two
        // rows on one link and fork a chain that is meant to be a line, so this unit follows nothing; the
        // shared thread key is what still ties it to the thread.
        log.info("Supersession found nothing live to claim: channel={}, threadKey={}", channel, threadKey);
        return Outcome.standalone();
    }

    /**
     * Retire the card still open on one in-app thread so a newer card about the same habit can take its
     * place — queued or already read, but never closed, which the caller has established by reading the
     * card the way the page reads it ({@code PreviousInAppFeedback}).
     *
     * <p>Two compare-and-sets, one per state a live card can be in, so a run racing this one claims it at
     * most once; a card another run already retired, or that has since been withheld, matches neither, and
     * the new card is written on its own. Never throws for the same reason {@link #supersede} does not:
     * losing the claim is ordinary, and the composed words are still owed.
     *
     * @param openId the open card, found by the caller on the thread this card is about to be written on
     */
    public Outcome replaceOpen(long workspaceId, UUID openId) {
        if (feedbackRepository.markSuperseded(workspaceId, openId) == 1
                || feedbackRepository.supersedeDelivered(workspaceId, openId) == 1) {
            return new Outcome(Disposition.SUPERSEDED, openId);
        }
        log.info("Open in-app card was already claimed by another run; written as new: target={}", openId);
        return Outcome.standalone();
    }
}
