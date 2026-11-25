package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The membership reasons for which Hephaestus withholds a practice on purpose, by a policy decision, not a failure.
 * Selection writes the role, bot, scope and checks reasons. Capture writes the readiness and fit reasons. Practice
 * start writes {@code REPLACED} when a newer review of the item measured the practice or can still measure it,
 * {@code SOURCES_MOVED} when its own sources moved and none does, and {@code READINESS_REFUSED} when they cannot be
 * verified. A replaced review writes {@code REPLACED}, or {@code SOURCES_MOVED} for a practice in feedback mode Off
 * that the newer push leaves out. An attempt that ended writes {@code NOT_RUN} for each practice it never measured.
 * Staging and admission write {@code ROLE_UNAVAILABLE} when the developer is no longer eligible,
 * {@code WORKSPACE_INACTIVE} when the workspace is not active, and {@code STALE_ROLLOUT_REVISION} when the workspace's
 * review settings changed while the review waited.
 *
 * <p>Every other membership reason is a failure of a run or of admission: a {@link ReviewStopReason} of an attempt
 * that failed or stopped, a candidate refusal, {@code COPY_AUTHORITY_UNAVAILABLE}, or a reason that only history
 * from before 1.0 holds.
 */
public final class ReviewWithholdings {

    public static final String ROLE_UNAVAILABLE = "ROLE_UNAVAILABLE";
    public static final String ROLE_AMBIGUOUS = "ROLE_AMBIGUOUS";
    public static final String BOT_AUTHOR = "BOT_AUTHOR";
    public static final String BOT_REVIEWER = "BOT_REVIEWER";
    public static final String OUTSIDE_REVIEW_SCOPE = "OUTSIDE_REVIEW_SCOPE";
    public static final String CHECKS_PENDING = "CHECKS_PENDING";
    public static final String CHECKS_AWAITING_APPROVAL = "CHECKS_AWAITING_APPROVAL";
    public static final String NO_CHECKS_REPORTED = "NO_CHECKS_REPORTED";
    public static final String WORKSPACE_INACTIVE = "WORKSPACE_INACTIVE";
    public static final String STALE_ROLLOUT_REVISION = ReviewStopReason.STALE_ROLLOUT_REVISION.name();

    /** A pinned practice whose reads the current source catalog cannot capture for the reviewed item. */
    public static final String SOURCE_CONTRACT_UNSUPPORTED = "source_contract_unsupported";

    /** A pinned item practice that declares a review event or About role that its item kind does not offer. */
    public static final String ITEM_CAPABILITY_UNSUPPORTED = "item_capability_unsupported";

    /**
     * Reasons of a practice that was never measured and is still owed: it waits for the item's checks, a newer
     * occasion replaced its review, its own sources moved, it was not ready, it never ran, or the work's evidence was
     * larger than a sandbox holds. Not ready covers sources that could not be verified at practice start or seal and too
     * little captured evidence. None of them says that the practice does not apply. The item's next readiness edge
     * selects it again, whatever that edge's occasions select. The one exception: occasions that all continue leave out a
     * practice in feedback mode Off that reviews at a milestone, unless its own version still owes it.
     */
    public static final List<String> CARRIED = List.of(
            CHECKS_PENDING,
            NO_CHECKS_REPORTED,
            CHECKS_AWAITING_APPROVAL,
            ReviewStopReason.REPLACED.name(),
            ReviewStopReason.SOURCES_MOVED.name(),
            ReviewStopReason.READINESS_REFUSED.name(),
            ReviewStopReason.NOT_RUN.name(),
            ReviewStopReason.EVIDENCE_TOO_LARGE.name());

    /**
     * Carried reasons that ask for a follow-up occasion at the same version, as no newer event may come: the sources
     * moved, the practice never ran, or a newer review took it over and then ended without measuring it.
     */
    public static final Set<String> FOLLOW_UP = Set.of(
            ReviewStopReason.SOURCES_MOVED.name(), ReviewStopReason.NOT_RUN.name(), ReviewStopReason.REPLACED.name());

    /**
     * The reason a practice that the run left unmeasured keeps: a run that ran out of time, ended early or lost its
     * access leaves it owed ({@code NOT_RUN}), and any other stop keeps its own reason.
     */
    public static ReviewStopReason unmeasured(ReviewStopReason stop) {
        return switch (stop) {
            case DEADLINE, TIMED_OUT, EXECUTION_INCOMPLETE, AUTHORITY_CHANGED -> ReviewStopReason.NOT_RUN;
            default -> stop;
        };
    }

    /**
     * Every reason for which Hephaestus withholds a practice on purpose, by a policy decision, not a failure. A practice
     * that never ran ({@code NOT_RUN}) is carried, but its review's execution did not finish.
     */
    public static final Set<String> DELIBERATE = Set.copyOf(Stream.concat(
                    CARRIED.stream().filter(reason -> !reason.equals(ReviewStopReason.NOT_RUN.name())),
                    Stream.of(
                            ROLE_UNAVAILABLE,
                            ROLE_AMBIGUOUS,
                            BOT_AUTHOR,
                            BOT_REVIEWER,
                            OUTSIDE_REVIEW_SCOPE,
                            WORKSPACE_INACTIVE,
                            STALE_ROLLOUT_REVISION,
                            SOURCE_CONTRACT_UNSUPPORTED,
                            ITEM_CAPABILITY_UNSUPPORTED))
            .toList());

    private ReviewWithholdings() {}
}
