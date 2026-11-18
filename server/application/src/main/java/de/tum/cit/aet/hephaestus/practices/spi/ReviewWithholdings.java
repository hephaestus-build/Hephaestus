package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The membership reasons for which Hephaestus withholds a practice on purpose, by a policy decision, not a failure.
 * Selection writes the role, bot, scope and checks reasons. Capture writes the readiness and fit reasons. Practice
 * start, a seal at a moved version and a replaced review write {@code REPLACED} or {@code READINESS_REFUSED}. Staging
 * and admission write {@code ROLE_UNAVAILABLE} when the developer is no longer eligible, {@code WORKSPACE_INACTIVE}
 * when the workspace is not active, and {@code STALE_ROLLOUT_REVISION} when the workspace's review settings changed
 * while the review waited.
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
     * occasion replaced its review, it was not ready, or the work's evidence was larger than a sandbox holds. Not ready
     * covers sources that could not be verified at practice start or seal and too little captured evidence. None of
     * them says that the practice does not apply. The item's next readiness edge selects it again, whatever that edge's
     * occasions select.
     */
    public static final List<String> CARRIED = List.of(
            CHECKS_PENDING,
            NO_CHECKS_REPORTED,
            CHECKS_AWAITING_APPROVAL,
            ReviewStopReason.REPLACED.name(),
            ReviewStopReason.READINESS_REFUSED.name(),
            ReviewStopReason.EVIDENCE_TOO_LARGE.name());

    /** Every reason for which Hephaestus withholds a practice on purpose, by a policy decision, not a failure. */
    public static final Set<String> DELIBERATE = Set.copyOf(Stream.concat(
                    CARRIED.stream(),
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
