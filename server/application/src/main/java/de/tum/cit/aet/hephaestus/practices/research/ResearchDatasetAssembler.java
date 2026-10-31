package de.tum.cit.aet.hephaestus.practices.research;

import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.research.ResearchPseudonyms.Kind;
import de.tum.cit.aet.hephaestus.practices.research.ResearchSnapshot.BasisRow;
import de.tum.cit.aet.hephaestus.practices.research.ResearchSnapshot.FeedbackRow;
import de.tum.cit.aet.hephaestus.practices.research.ResearchSnapshot.ResultRow;
import de.tum.cit.aet.hephaestus.practices.research.ResearchSnapshot.ReviewPracticeRow;
import de.tum.cit.aet.hephaestus.practices.research.ResearchSnapshot.ReviewRow;
import de.tum.cit.aet.hephaestus.practices.research.ResearchSnapshot.ReviewSubjectRow;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewUsageAttempt;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewUsageSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Turns one snapshot into the schema-1 file. It has no I/O: everything it needs is passed in, so it
 * can be tested on fixtures alone.
 *
 * <p>Only allow-listed columns are read from the snapshot. Each record kind gets keys from its own
 * domain, and every list is sorted by key so the file reveals no database order.
 */
final class ResearchDatasetAssembler {

    static final String BUNDLED = "BUNDLED_CATALOG";
    static final String OTHER = "OTHER";
    static final String MATCHES_EXPORTING_BUNDLE = "MATCHES_EXPORTING_BUNDLE";
    static final String NOT_MATCHED = "NOT_MATCHED";

    /** A stored run reason is exported only if it has the shape of a code; anything else is not trusted as one. */
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,47}$");

    private ResearchDatasetAssembler() {}

    /** One practice as the exporting release's bundled catalogue ships it. */
    record BundledStandard(String reviewDigest, String criteria) {}

    /**
     * @param participantKeys account id → the participant key printed in the file, for the participants
     *     that the file names; what the protected mapping stores
     */
    record Assembled(ResearchDataset dataset, Map<Long, String> participantKeys) {}

    /**
     * @param bundled slug → the definition this release ships under that slug, from the bundled loader
     * @param standardSource where a reader resolves the bundled content and digests
     */
    static Assembled assemble(
            ResearchSnapshot snapshot,
            UUID datasetId,
            Instant from,
            Instant to,
            Instant useUntil,
            Map<String, BundledStandard> bundled,
            ResearchDataset.StandardSource standardSource,
            ResearchPseudonyms keys) {
        Set<Long> authorised = snapshot.grants().keySet();
        Set<UUID> resultIds = new HashSet<>();
        for (ResultRow result : snapshot.results()) {
            if (authorised.contains(result.developerId())) resultIds.add(result.id());
        }
        Set<UUID> problemIds = snapshot.problems().stream()
                .filter(problem -> resultIds.contains(problem.resultId()))
                .map(ResearchSnapshot.ProblemRow::id)
                .collect(Collectors.toSet());
        Map<UUID, List<BasisRow>> basisByFeedback =
                snapshot.basis().stream().collect(Collectors.groupingBy(BasisRow::feedbackId));

        // A piece of feedback is either exported with all of its support or not at all.
        List<FeedbackRow> keptFeedback = new ArrayList<>();
        int excluded = 0;
        for (FeedbackRow feedback : snapshot.feedback()) {
            if (!authorised.contains(feedback.developerId())) continue;
            boolean complete = basisByFeedback.getOrDefault(feedback.id(), List.of()).stream()
                    .allMatch(basis -> resultIds.contains(basis.resultId())
                            && (basis.problemId() == null || problemIds.contains(basis.problemId())));
            if (complete) keptFeedback.add(feedback);
            else excluded++;
        }
        Set<UUID> feedbackIds = keptFeedback.stream().map(FeedbackRow::id).collect(Collectors.toSet());

        List<ResultRow> keptResults = snapshot.results().stream()
                .filter(result -> resultIds.contains(result.id()))
                .toList();
        Set<Long> participants = new HashSet<>();
        Set<UUID> reviews = new HashSet<>();
        Set<Long> items = new HashSet<>();
        Set<Long> practices = new HashSet<>();
        Set<Long> versions = new HashSet<>();
        for (ResultRow result : keptResults) {
            participants.add(result.developerId());
            reviews.add(result.reviewId());
            items.add(result.itemId());
            practices.add(result.practiceId());
            Long version = result.practiceVersionId();
            if (version != null) versions.add(version);
        }
        for (FeedbackRow feedback : keptFeedback) {
            participants.add(feedback.developerId());
            reviews.add(feedback.reviewId());
            Long item = feedback.itemId();
            if (item != null) items.add(item);
        }
        Map<UUID, List<ReviewPracticeRow>> runsByReview = new HashMap<>();
        for (ReviewPracticeRow run : snapshot.reviewPractices()) {
            if (!reviews.contains(run.reviewId())) continue;
            runsByReview
                    .computeIfAbsent(run.reviewId(), ignored -> new ArrayList<>())
                    .add(run);
            practices.add(run.practiceId());
            Long version = run.practiceVersionId();
            if (version != null) versions.add(version);
        }

        // Whole-review facts are about every subject of the review, not only whoever has a row in the
        // window. Each such subject is named in the file, bound into the protected mapping and rechecked at
        // the final grant check. A review with any other subject discloses no whole-review fact and no key.
        Map<UUID, ReviewSubjectRow> subjects =
                snapshot.reviewSubjects().stream().collect(Collectors.toMap(ReviewSubjectRow::reviewId, row -> row));
        Map<UUID, ReviewSubjectScope> scopes = new HashMap<>();
        for (UUID review : reviews) {
            ReviewSubjectRow row = subjects.get(review);
            ReviewSubjectScope scope = ReviewSubjectScope.of(row, authorised);
            scopes.put(review, scope);
            if (scope == ReviewSubjectScope.ALL_SUBJECTS_PARTICIPATING) {
                participants.addAll(Objects.requireNonNull(row).developers());
            }
        }

        Map<Long, String> participantKeys = new TreeMap<>();
        for (Long account : participants) participantKeys.put(account, keys.key(Kind.PARTICIPANT, account));

        // Practices and versions: a slug is named only if the exporting bundle carries it, and content
        // only where the version's review digest equals that bundle's.
        Map<Long, String> bundledSlugs = new HashMap<>();
        List<ResearchDataset.Practice> practiceRecords = new ArrayList<>();
        for (var practice : snapshot.practices()) {
            if (!practices.contains(practice.id())) continue;
            String slug = practice.catalogSlug();
            boolean shipped = slug != null && bundled.containsKey(slug);
            if (shipped) bundledSlugs.put(practice.id(), slug);
            practiceRecords.add(new ResearchDataset.Practice(
                    keys.key(Kind.PRACTICE, practice.id()), shipped ? BUNDLED : OTHER, shipped ? slug : null));
        }
        Set<String> matchedSlugs = new HashSet<>();
        List<ResearchDataset.PracticeVersion> versionRecords = new ArrayList<>();
        for (var version : snapshot.practiceVersions()) {
            if (!versions.contains(version.id())) continue;
            String slug = bundledSlugs.get(version.practiceId());
            BundledStandard shipped = slug == null ? null : bundled.get(slug);
            boolean matches = shipped != null && shipped.reviewDigest().equals(version.reviewDigest());
            if (matches) matchedSlugs.add(slug);
            versionRecords.add(new ResearchDataset.PracticeVersion(
                    keys.key(Kind.PRACTICE_VERSION, version.id()),
                    keys.key(Kind.PRACTICE, version.practiceId()),
                    version.number(),
                    matches ? MATCHES_EXPORTING_BUNDLE : NOT_MATCHED,
                    matches ? slug : null));
        }
        List<ResearchDataset.Standard> standards = new ArrayList<>();
        for (String slug : matchedSlugs) {
            BundledStandard shipped = Objects.requireNonNull(bundled.get(slug));
            standards.add(
                    new ResearchDataset.Standard(slug, shipped.reviewDigest(), shipped.criteria(), standardSource));
        }

        List<ResearchDataset.Item> itemRecords = snapshot.items().stream()
                .filter(item -> items.contains(item.id()))
                .map(item -> new ResearchDataset.Item(keys.key(Kind.ITEM, item.id()), item.kind()))
                .toList();
        List<ResearchDataset.Review> reviewRecords = new ArrayList<>();
        for (ReviewRow review : snapshot.reviews()) {
            if (!reviews.contains(review.id())) continue;
            ReviewSubjectScope scope = Objects.requireNonNull(scopes.get(review.id()));
            boolean whole = scope == ReviewSubjectScope.ALL_SUBJECTS_PARTICIPATING;
            List<String> edges = whole
                    ? Objects.requireNonNull(subjects.get(review.id())).developers().stream()
                            .map(account -> participant(participantKeys, account))
                            .sorted()
                            .toList()
                    : List.of();
            reviewRecords.add(new ResearchDataset.Review(
                    keys.key(Kind.REVIEW, review.id()),
                    review.kind(),
                    review.origin(),
                    review.status(),
                    day(review.orderedAt()),
                    dayOrNull(review.completedAt()),
                    scope.name(),
                    edges,
                    sortedBy(
                            runsByReview.getOrDefault(review.id(), List.of()).stream()
                                    .map(run -> reviewPractice(run, whole, keys))
                                    .toList(),
                            run -> run.practice() + run.practiceVersion()),
                    usage(scope, snapshot.usage().get(review.id()), keys)));
        }
        List<ResearchDataset.Result> resultRecords = keptResults.stream()
                .map(result -> new ResearchDataset.Result(
                        keys.key(Kind.RESULT, result.id()),
                        participant(participantKeys, result.developerId()),
                        keys.key(Kind.REVIEW, result.reviewId()),
                        keys.key(Kind.ITEM, result.itemId()),
                        keys.key(Kind.PRACTICE, result.practiceId()),
                        keyOrNull(keys, Kind.PRACTICE_VERSION, result.practiceVersionId()),
                        result.itemVersionRecorded(),
                        result.outcome(),
                        result.severity(),
                        result.evidenceVersion(),
                        proof(result),
                        result.coverageRecorded() ? "PRESENT" : "NOT_RECORDED",
                        result.endedAt() == null ? "OPEN" : "ENDED",
                        day(result.recordedAt()),
                        dayOrNull(result.endedAt()),
                        result.endReason(),
                        result.activeMark()))
                .toList();
        List<ResearchDataset.Problem> problemRecords = snapshot.problems().stream()
                .filter(problem -> problemIds.contains(problem.id()))
                .map(problem -> new ResearchDataset.Problem(
                        keys.key(Kind.PROBLEM, problem.id()),
                        keys.key(Kind.RESULT, problem.resultId()),
                        problem.position(),
                        problem.severity()))
                .toList();

        Map<UUID, List<ResearchDataset.Decision>> decisions = new HashMap<>();
        for (var decision : snapshot.decisions()) {
            if (!feedbackIds.contains(decision.feedbackId())) continue;
            decisions
                    .computeIfAbsent(decision.feedbackId(), ignored -> new ArrayList<>())
                    .add(new ResearchDataset.Decision(decision.action(), day(decision.decidedAt())));
        }
        Map<UUID, List<ResearchDataset.Response>> responses = new HashMap<>();
        for (var response : snapshot.responses()) {
            if (!feedbackIds.contains(response.feedbackId())) continue;
            responses
                    .computeIfAbsent(response.feedbackId(), ignored -> new ArrayList<>())
                    .add(new ResearchDataset.Response(
                            response.resolution(), response.usefulness(), day(response.createdAt())));
        }
        List<ResearchDataset.Feedback> feedbackRecords = keptFeedback.stream()
                .map(feedback -> new ResearchDataset.Feedback(
                        keys.key(Kind.FEEDBACK, feedback.id()),
                        participant(participantKeys, feedback.developerId()),
                        keys.key(Kind.REVIEW, feedback.reviewId()),
                        keyOrNull(keys, Kind.ITEM, feedback.itemId()),
                        feedback.destination(),
                        feedback.state(),
                        feedback.stopReason(),
                        day(feedback.createdAt()),
                        dayOrNull(feedback.deliveredAt()),
                        keyOrNull(
                                keys,
                                Kind.FEEDBACK,
                                feedbackIds.contains(feedback.replacesId()) ? feedback.replacesId() : null),
                        feedback.replacesId() != null && !feedbackIds.contains(feedback.replacesId()),
                        basisByFeedback.getOrDefault(feedback.id(), List.of()).stream()
                                .sorted(Comparator.comparingInt(BasisRow::ordinal))
                                .map(basis -> new ResearchDataset.Basis(
                                        keys.key(Kind.RESULT, basis.resultId()),
                                        keyOrNull(keys, Kind.PROBLEM, basis.problemId())))
                                .toList(),
                        sortedBy(decisions.getOrDefault(feedback.id(), List.of()), d -> d.date() + d.action()),
                        sortedBy(
                                responses.getOrDefault(feedback.id(), List.of()),
                                r -> r.date() + r.resolution() + r.usefulness())))
                .toList();

        var dataset = new ResearchDataset(
                ResearchDataset.SCHEMA,
                ResearchDataset.VERSION,
                datasetId.toString(),
                ResearchDataset.CLASSIFICATION,
                ResearchDisclosure.PURPOSE,
                new ResearchDataset.Research(
                        snapshot.question().organization(), snapshot.question().noticeVersion()),
                new ResearchDataset.Window(day(from), day(to)),
                snapshot.cutoff().toString(),
                day(useUntil),
                ResearchDataset.LIMITATIONS,
                new ResearchDataset.Counts(
                        participantKeys.size(),
                        resultRecords.size(),
                        problemRecords.size(),
                        feedbackRecords.size(),
                        excluded),
                sortedBy(
                        participantKeys.values().stream()
                                .map(ResearchDataset.Participant::new)
                                .toList(),
                        ResearchDataset.Participant::key),
                sortedBy(standards, ResearchDataset.Standard::slug),
                sortedBy(practiceRecords, ResearchDataset.Practice::key),
                sortedBy(versionRecords, ResearchDataset.PracticeVersion::key),
                sortedBy(itemRecords, ResearchDataset.Item::key),
                sortedBy(reviewRecords, ResearchDataset.Review::key),
                sortedBy(resultRecords, ResearchDataset.Result::key),
                sortedBy(problemRecords, ResearchDataset.Problem::key),
                sortedBy(feedbackRecords, ResearchDataset.Feedback::key));
        return new Assembled(dataset, Map.copyOf(participantKeys));
    }

    /** Derived from the identities actually recorded, not from the evidence-version number alone. */
    static String proof(ResultRow result) {
        if (result.evidenceVersion() == Result.MIGRATED_EVIDENCE_VERSION)
            return "LEGACY_CAPTURE_CONTRACT_NOT_ESTABLISHED";
        return result.practiceVersionId() != null && result.itemVersionRecorded()
                ? "VERSION_IDENTITIES_RECORDED"
                : "VERSION_IDENTITIES_INCOMPLETE";
    }

    private static ResearchDataset.ReviewPractice reviewPractice(
            ReviewPracticeRow run, boolean wholeReviewFacts, ResearchPseudonyms keys) {
        String reason = run.reason();
        ResearchDataset.Assignments assignments = !wholeReviewFacts
                ? null
                : run.assignments() == 0
                        ? new ResearchDataset.Assignments(false, null, null, null, null)
                        : new ResearchDataset.Assignments(
                                true, run.listed(), run.judged(), run.reused(), run.withheld());
        return new ResearchDataset.ReviewPractice(
                keys.key(Kind.PRACTICE, run.practiceId()),
                keyOrNull(keys, Kind.PRACTICE_VERSION, run.practiceVersionId()),
                run.status(),
                reason == null ? null : CODE.matcher(reason).matches() ? reason : "UNLISTED_CODE",
                wholeReviewFacts ? run.itemsListed() : null,
                wholeReviewFacts ? run.listingComplete() : null,
                assignments);
    }

    /**
     * The owner's snapshot as it is: its totals, its priced flag and every retained attempt. No sum is
     * recomputed, reasoning is not added to output, and nothing is allocated to a participant.
     */
    static ResearchDataset.Usage usage(
            ReviewSubjectScope scope, @Nullable ReviewUsageSnapshot usage, ResearchPseudonyms keys) {
        String allocation = "WHOLE_REVIEW_NOT_ALLOCATED";
        String coverage = "NOT_RECORDED";
        if (scope != ReviewSubjectScope.ALL_SUBJECTS_PARTICIPATING) {
            return new ResearchDataset.Usage(
                    "UNAVAILABLE", scope.name(), allocation, coverage, null, null, null, null, null, List.of());
        }
        if (usage == null) {
            return new ResearchDataset.Usage(
                    "NO_RETAINED_LEDGER_ROWS", null, allocation, coverage, null, null, null, null, null, List.of());
        }
        List<ResearchDataset.UsageAttempt> attempts = new ArrayList<>();
        for (ReviewUsageAttempt attempt : usage.attempts()) {
            attempts.add(new ResearchDataset.UsageAttempt(
                    keys.key(Kind.JOB, attempt.jobId()),
                    attempt.attempt(),
                    attempt.calls(),
                    attempt.inputTokens(),
                    attempt.outputTokens(),
                    attempt.reasoningTokens(),
                    attempt.cacheReadTokens(),
                    attempt.cacheWriteTokens(),
                    amount(attempt.costUsd()),
                    attempt.pricingState().name()));
        }
        return new ResearchDataset.Usage(
                "RETAINED",
                null,
                allocation,
                coverage,
                usage.priced(),
                amount(usage.costUsd()),
                usage.calls(),
                usage.inputTokens(),
                usage.outputTokens(),
                sortedBy(attempts, a -> a.job() + ":" + String.format("%010d", a.attempt())));
    }

    private static @Nullable String amount(@Nullable BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static <T> List<T> sortedBy(List<T> values, Function<T, String> key) {
        return values.stream().sorted(Comparator.comparing(key)).toList();
    }

    static String day(Instant instant) {
        return LocalDate.ofInstant(instant, ZoneOffset.UTC).toString();
    }

    private static @Nullable String keyOrNull(ResearchPseudonyms keys, Kind kind, @Nullable Object id) {
        return id == null ? null : keys.key(kind, id);
    }

    private static @Nullable String dayOrNull(@Nullable Instant instant) {
        return instant == null ? null : day(instant);
    }

    private static String participant(Map<Long, String> participantKeys, long accountId) {
        return Objects.requireNonNull(participantKeys.get(accountId));
    }
}
