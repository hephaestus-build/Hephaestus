package de.tum.cit.aet.hephaestus.practices.feedback;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Stable cross-run identity of a {@link Feedback} delivery <em>unit</em> (ADR 0021, F-16) — the join key
 * that lets a re-review SUPERSEDE the prior delivery and edit its comment in place instead of posting a
 * fresh one.
 *
 * <p><strong>Identity is the destination, NOT the content.</strong> The unit is keyed by <em>where it is
 * delivered</em>: the artifact {@code (artifact_kind, artifact_id)}, the {@code recipient}, and the
 * {@code surface}. Two reviews of the same PR deliver the same in-context summary unit to the same author →
 * same continuity key → the second supersedes the first. The observations it references are the changing
 * <em>content</em>, recorded separately, so the unit's identity is stable even as its body churns between
 * reviews.
 *
 * <p><strong>What "the destination" means differs by lane, and that difference is the design.</strong> An
 * in-context note is delivered <em>onto a piece of work</em>, so the artifact is where it lands and
 * {@link #compute} keys on it. The two longitudinal lanes land on nothing — a in-app card and a
 * mentor turn are about a <em>way of working</em> observed across several pieces of work — so their destination is
 * the practice, and {@link #forPractice} keys on that instead. A second card about the same practice
 * replaces the first rather than stacking beside it; a second card about a different practice is a different
 * thread even though both land on the same page.
 *
 * <p>One computation for all three lanes, deliberately. The key is only useful because "find the queued
 * message I would replace" is a lookup on an indexed column, and two vocabularies in one indexed column
 * make that lookup unwritable.
 *
 * <p>Locale-safe (Locale.ROOT) lower-cased SHA-256 hex, 64 chars, matching {@code feedback.thread_key
 * VARCHAR(64)}. Pure and side-effect free.
 */
public final class FeedbackThreadKey {

    private static final char SEP = '\u001F'; // ASCII unit separator

    /**
     * The discriminator {@link #forPractice} puts where an artifact kind goes, so the two vocabularies
     * cannot meet. Without it the practice thread for a practice slugged {@code "42"} is bit-identical to the
     * artifact thread for id 42 with no kind, and one card could retire the other. Every artifact kind is
     * namespaced ({@code scm.pull_request}, {@code chat.conversation_thread}), so an undotted word is a
     * shape none of them can take.
     */
    private static final String PRACTICE_SCOPE = "practice";

    private FeedbackThreadKey() {}

    /**
     * Compute the stable continuity key for a unit that lands on a piece of work.
     *
     * @param artifactKind the artifact-type discriminator (e.g. {@code PULL_REQUEST}); empty string when the
     *     unit is not artifact-anchored (a dashboard digest)
     * @param artifactId the artifact id, or {@code null} when not artifact-anchored
     * @param recipientUserId the user the unit is delivered to (required)
     * @param surface the delivery surface (required)
     * @return the lowercase SHA-256 hex digest (exactly 64 characters)
     */
    public static String compute(
            String artifactKind, @Nullable Long artifactId, long recipientUserId, FeedbackChannel surface) {
        return canonical(
                artifactKind == null ? "" : artifactKind,
                artifactId == null ? "" : String.valueOf(artifactId),
                recipientUserId,
                surface);
    }

    /**
     * Compute the stable continuity key for a longitudinal unit — one about a way of working rather than about a
     * piece of work.
     *
     * <p>It occupies the same tuple as {@link #compute}, with the practice standing where the artifact
     * stands, so the two lanes share one digest and one column vocabulary while staying disjoint — see
     * {@link #PRACTICE_SCOPE}. A blank slug is refused rather than quietly keyed, because it would
     * collapse every practice of one person onto a single thread and let one card retire another about
     * something else entirely.
     *
     * @param practiceSlug the practice this unit is about (required, non-blank)
     * @param recipientUserId the user the unit is delivered to (required)
     * @param surface the delivery surface (required)
     * @return the lowercase SHA-256 hex digest (exactly 64 characters)
     */
    public static String forPractice(String practiceSlug, long recipientUserId, FeedbackChannel surface) {
        Objects.requireNonNull(practiceSlug, "practiceSlug");
        if (practiceSlug.isBlank()) {
            throw new IllegalArgumentException("practiceSlug must not be blank");
        }
        return canonical(PRACTICE_SCOPE, practiceSlug, recipientUserId, surface);
    }

    private static String canonical(String kind, String locus, long recipientUserId, FeedbackChannel surface) {
        Objects.requireNonNull(surface, "surface");
        String canonical = new StringBuilder()
                .append(kind)
                .append(SEP)
                .append(locus)
                .append(SEP)
                .append(recipientUserId)
                .append(SEP)
                .append(surface.name())
                .toString();
        return sha256Hex(canonical);
    }

    private static String sha256Hex(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
