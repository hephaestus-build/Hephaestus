package de.tum.cit.aet.hephaestus.practices.research;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Schema {@value #SCHEMA} version {@value #VERSION}: the file a research team receives. Every field is
 * one of:
 * <ul>
 *   <li>a closed vocabulary,</li>
 *   <li>a count, a token total or a decimal amount,</li>
 *   <li>a UTC day,</li>
 *   <li>a per-export key, or</li>
 *   <li>public content shipped in the exporting release's bundled practice catalogue.</li>
 * </ul>
 * There is no participant-authored or model-written text, code, quote, citation, path, URL, commit
 * or source hash of a person's work, provider or database identifier, name, login or email.
 *
 * <p>A null is an honest "not recorded" or "withheld" and is always written out. It is never replaced
 * by a zero or an invented default.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
record ResearchDataset(
        String schema,
        int schemaVersion,
        String datasetId,
        String classification,
        String purpose,
        Research research,
        Window window,
        String cutoff,
        String useUntil,
        List<String> limitations,
        Counts counts,
        List<Participant> participants,
        List<Standard> standards,
        List<Practice> practices,
        List<PracticeVersion> practiceVersions,
        List<Item> items,
        List<Review> reviews,
        List<Result> results,
        List<Problem> problems,
        List<Feedback> feedback) {

    static final String SCHEMA = "hephaestus.research-dataset";
    static final int VERSION = 1;
    static final String CLASSIFICATION = "PSEUDONYMOUS_PERSONAL_DATA";

    /** What the file deliberately does not contain or claim, so its absence is not read as "none happened". */
    static final List<String> LIMITATIONS = List.of(
            // Summaries, explanations, bodies, responses' comments, decision notes, mark reasons, evidence.
            "FREE_TEXT_EXCLUDED",
            // Usage belongs to a whole review. It is never split across, or allocated to, participants.
            "USAGE_WHOLE_REVIEW_NOT_ALLOCATED",
            // Whole-review facts are withheld unless every subject of the review is a participant.
            "WHOLE_REVIEW_FACTS_ONLY_WHEN_ALL_SUBJECTS_PARTICIPATE",
            // A review's subjectParticipants lists every subject of its whole-review facts, including
            // participants with no result or feedback row of their own in the window.
            "REVIEW_SUBJECT_EDGES_COVER_WHOLE_REVIEW_FACTS",
            // The usage owner reports totals per attempt; it does not say every call was measured.
            "USAGE_MEASUREMENT_COVERAGE_NOT_RECORDED",
            // Reported output tokens already include reasoning tokens; do not add them again.
            "REASONING_TOKENS_INCLUDED_IN_OUTPUT",
            // evidenceVersion 0 was migrated: the capture contract was not established for it.
            "LEGACY_RESULTS_CAPTURE_CONTRACT_NOT_ESTABLISHED",
            // resultCoverageRecord marks a per-result capture record, not a population denominator.
            "RESULT_COVERAGE_IS_PER_RESULT_MARKER",
            // Standard content appears only where a version's review digest equals the exporting bundle's.
            "STANDARD_CONTENT_ONLY_FOR_EXPORTING_BUNDLE_MATCHES",
            // Keys are only stable inside this file; other values can still link files together.
            "KEYS_PER_EXPORT_NOT_ANONYMOUS");

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Research(String organization, String noticeVersion) {}

    /** UTC days, {@code from} inclusive and {@code to} exclusive. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Window(String from, String to) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Counts(int participants, int results, int problems, int feedback, int feedbackExcludedIncompleteBasis) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Participant(String key) {}

    /**
     * A public curated standard as the exporting release ships it. {@code criteria} is the bundled
     * review criteria text. It is public catalogue content, never an instance's effective or custom
     * definition. Practice scripts are never included. {@code source} names where the text and digest
     * can be resolved.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Standard(String slug, String reviewDigest, String criteria, StandardSource source) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record StandardSource(
            String catalogResource,
            String releaseVersion,
            String releaseChannel,
            @Nullable String releaseCommit) {}

    /** {@code bundledSlug} only when the practice came from a slug the exporting bundle still carries. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Practice(String key, String origin, @Nullable String bundledSlug) {}

    /**
     * {@code reviewDefinition} is {@code MATCHES_EXPORTING_BUNDLE} when this version's review digest
     * equals the digest of the definition the exporting bundle ships under the practice's slug. Only
     * the review-relevant fields are digested: criteria, script, reads, reviewer, scope and scan. A
     * match therefore says nothing about name, description or guidance, which may have been changed.
     * Every other version is {@code NOT_MATCHED}. That covers custom versions and older shipped ones.
     * Their publicness is not inferred, and no digest or content is given.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record PracticeVersion(
            String key,
            String practice,
            int number,
            String reviewDefinition,
            @Nullable String standard) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Item(String key, String kind) {}

    /**
     * {@code subjectScope} says whether every subject of this whole review is a participant. When it is
     * not {@code ALL_SUBJECTS_PARTICIPATING}, the whole-review facts are null:
     * {@code practices[].itemsListed}, {@code listingComplete}, {@code assignments}, and the usage
     * amounts. {@code usage.unavailableReason} names why, and {@code subjectParticipants} is empty, so no
     * other person's key is exposed.
     *
     * <p>When it is {@code ALL_SUBJECTS_PARTICIPATING}, {@code subjectParticipants} lists the key of
     * every subject of those facts. That includes a participant whose own result lies outside the window,
     * or who is known only from an unjudged assignment. Removing a participant downstream therefore
     * means removing every review fact whose edges contain their key, not only their own rows.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Review(
            String key,
            String kind,
            String origin,
            String status,
            String orderedDate,
            @Nullable String completedDate,
            String subjectScope,
            List<String> subjectParticipants,
            List<ReviewPractice> practices,
            Usage usage) {}

    /**
     * One practice run of the review, as the review owner recorded it. {@code reason} is a recorded
     * code, or {@code UNLISTED_CODE} when the stored value is not a code.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record ReviewPractice(
            String practice,
            @Nullable String practiceVersion,
            String status,
            @Nullable String reason,
            @Nullable Integer itemsListed,
            @Nullable Boolean listingComplete,
            @Nullable Assignments assignments) {}

    /** Assignment counts by state. {@code recorded} false means the run has no assignment rows, as migrated runs do. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Assignments(
            boolean recorded,
            @Nullable Integer listed,
            @Nullable Integer judged,
            @Nullable Integer reused,
            @Nullable Integer withheld) {}

    /**
     * Canonical retained usage of the whole review, all attempts, as the accounting owner reports it.
     * {@code state} is one of:
     * <ul>
     *   <li>{@code RETAINED}: the ledger holds rows for this review.</li>
     *   <li>{@code NO_RETAINED_LEDGER_ROWS}: no rows remain. This is not zero usage.</li>
     *   <li>{@code UNAVAILABLE}: withheld for the given reason.</li>
     * </ul>
     * {@code costUsd} is null unless every attempt is priced. {@code "0"} is a measured no-charge
     * amount, not a missing one. An attempt whose {@code includesUpperBound} is true adds the upper bound of a call
     * whose usage was not measured: its cost is at most that amount, not an exact one.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Usage(
            String state,
            @Nullable String unavailableReason,
            String allocation,
            String measurementCoverage,
            @Nullable Boolean priced,
            @Nullable String costUsd,
            @Nullable Long calls,
            @Nullable Long inputTokens,
            @Nullable Long outputTokens,
            List<UsageAttempt> attempts) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record UsageAttempt(
            String job,
            int attempt,
            long calls,
            long inputTokens,
            long outputTokens,
            long reasoningTokens,
            long cacheReadTokens,
            long cacheWriteTokens,
            @Nullable String costUsd,
            String pricingState,
            boolean includesUpperBound) {}

    /**
     * {@code proof} is one of:
     * <ul>
     *   <li>{@code LEGACY_CAPTURE_CONTRACT_NOT_ESTABLISHED}: evidenceVersion 0.</li>
     *   <li>{@code VERSION_IDENTITIES_RECORDED}: both the practice version and the item version are
     *       recorded.</li>
     *   <li>{@code VERSION_IDENTITIES_INCOMPLETE}: either one is missing.</li>
     * </ul>
     * Retained version and end markers are kept on legacy rows too. {@code currentness} {@code OPEN}
     * only means no end was recorded.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Result(
            String key,
            String participant,
            String review,
            String item,
            String practice,
            @Nullable String practiceVersion,
            boolean itemVersionRecorded,
            String outcome,
            @Nullable String severity,
            int evidenceVersion,
            String proof,
            String resultCoverageRecord,
            String currentness,
            String recordedDate,
            @Nullable String endedDate,
            @Nullable String endReason,
            @Nullable String mark) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Problem(String key, String result, int position, String severity) {}

    /**
     * Present only when every basis reference is in this file. {@code basis} empty means none was
     * recorded, not that some was left out.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Feedback(
            String key,
            String participant,
            String review,
            @Nullable String item,
            String destination,
            String state,
            @Nullable String stopReason,
            String createdDate,
            @Nullable String deliveredDate,
            @Nullable String replaces,
            boolean replacesNotExported,
            List<Basis> basis,
            List<Decision> decisions,
            List<Response> responses) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Basis(String result, @Nullable String problem) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Decision(String action, String date) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Response(@Nullable String resolution, @Nullable String usefulness, String date) {}
}
