package de.tum.cit.aet.hephaestus.agent.composer;

import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedback;
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackPackage;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionTrace;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionTrace.CopySupport;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionTrace.QuietDecision;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionTrace.Support;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.PreparedPackage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The preparation requests one composition sends: the review on the work as one public package, each private piece as
 * its own package, and a withholding for each destination no decision was composed for.
 */
final class CompositionPackages {
    private CompositionPackages() {}

    static FeedbackPackage review(ReviewOnWork.ComposedReview review, @Nullable String publicationContext) {
        var refs = new ArrayList<Evidence.Ref>();
        if (review.summary() != null) refs.addAll(review.summary().basedOn());
        review.inline().forEach(note -> refs.addAll(note.basedOn()));
        review.withheld().forEach(decision -> refs.addAll(decision.basedOn()));
        var resultIds = distinct(refs);
        boolean quiet = review.summary() == null && review.inline().isEmpty();
        var composition = new ArrayList<ComposedFeedback>();
        if (!resultIds.isEmpty()) {
            var copies = new ArrayList<CopySupport>();
            if (review.summary() != null)
                copies.add(new CopySupport(
                        FeedbackCopyKind.SUMMARY, 0, support(review.summary().basedOn())));
            for (int index = 0; index < review.inline().size(); index++)
                copies.add(new CopySupport(
                        FeedbackCopyKind.LINE_NOTE,
                        index + (review.summary() == null ? 0 : 1),
                        support(review.inline().get(index).basedOn())));
            composition.add(new ComposedFeedback(
                    FeedbackDestination.ON_WORK,
                    resultIds,
                    review.summary() == null ? null : review.summary().body(),
                    review.inline().stream()
                            .map(note -> new ComposedFeedback.Note(
                                    UUID.fromString(note.anchor().problemId()),
                                    note.anchor().citationIndex(),
                                    note.body()))
                            .toList(),
                    quiet ? FeedbackStopReason.COMPOSER_WITHHELD : null,
                    new FeedbackCompositionTrace(
                            copies,
                            review.withheld().stream()
                                    .map(decision -> new QuietDecision(
                                            support(decision.basedOn()), decision.reason(), decision.witnessIds()))
                                    .toList(),
                            null,
                            null),
                    null));
        }
        return request(composition, true, publicationContext);
    }

    static FeedbackPackage piece(
            PrivateFeedback.Piece piece,
            List<PrivateFeedback.PreparedTarget> preparedTargets,
            @Nullable String primaryResultId) {
        boolean quiet = !piece.delivers();
        boolean card = piece.destination().equals("PRACTICE_PAGE") && !quiet;
        var destination = FeedbackDestination.valueOf(piece.destination());
        String replaces = piece.action().equals("SUPERSEDE")
                ? preparedTargets.stream()
                        .filter(target -> target.threadKey().equals(piece.supersedesThreadKey())
                                && target.destination().equals(piece.destination())
                                && target.practiceSlug().equals(piece.practiceSlug()))
                        .map(PrivateFeedback.PreparedTarget::feedbackId)
                        .findFirst()
                        .orElse(null)
                : null;
        var composed = new ComposedFeedback(
                destination,
                distinct(piece.basedOn()),
                quiet || card ? null : piece.feedbackBody(),
                List.of(),
                quiet ? FeedbackStopReason.COMPOSER_WITHHELD : null,
                new FeedbackCompositionTrace(
                        quiet
                                ? List.of()
                                : List.of(new CopySupport(
                                        destination == FeedbackDestination.CONVERSATION
                                                ? FeedbackCopyKind.CHAT_TURN
                                                : FeedbackCopyKind.SUMMARY,
                                        0,
                                        support(piece.basedOn()))),
                        quiet
                                ? List.of(new QuietDecision(
                                        support(piece.basedOn()),
                                        piece.withholdReason() == null ? "BELOW_BAR" : piece.withholdReason(),
                                        piece.witnessIds() == null ? List.of() : piece.witnessIds()))
                                : List.of(),
                        replaces == null ? null : UUID.fromString(replaces),
                        primaryResultId == null ? null : UUID.fromString(primaryResultId)),
                card && piece.title() != null && piece.body() != null && piece.nextStep() != null
                        ? new ComposedFeedback.Page(piece.title(), piece.body(), piece.nextStep())
                        : null);
        return request(List.of(composed), false, null);
    }

    /** A destination no decision was composed for: withheld on these results, with the reason recorded. */
    static FeedbackPackage withheld(FeedbackDestination destination, FeedbackStopReason reason, List<UUID> resultIds) {
        var support = resultIds.stream().map(id -> new Support(id, null)).toList();
        var composed = new ComposedFeedback(
                destination,
                resultIds,
                null,
                List.of(),
                reason,
                new FeedbackCompositionTrace(
                        List.of(),
                        List.of(new QuietDecision(support, QuietDecision.NOT_COMPOSED, List.of())),
                        null,
                        destination == FeedbackDestination.ON_WORK ? null : resultIds.getFirst()),
                null);
        return request(List.of(composed), destination == FeedbackDestination.ON_WORK, null);
    }

    private static FeedbackPackage request(
            List<ComposedFeedback> composition, boolean publicComposition, @Nullable String publicationContext) {
        var basis = new LinkedHashSet<UUID>();
        composition.forEach(piece -> basis.addAll(piece.resultIds()));
        return new FeedbackPackage(
                List.copyOf(basis), composition, publicComposition, publicComposition ? publicationContext : null);
    }

    static FeedbackPackage pieces(
            List<PrivateFeedback.Piece> pieces,
            List<PrivateFeedback.PreparedTarget> targets,
            Function<PrivateFeedback.Piece, @Nullable String> primary) {
        var composition = new ArrayList<ComposedFeedback>();
        pieces.forEach(piece ->
                composition.addAll(piece(piece, targets, primary.apply(piece)).composition()));
        return request(composition, false, null);
    }

    /** The checked drafts of one destination. Only the exact checked draft may be published, once. */
    static final class Drafts {
        private final UUID reviewId;
        private final Consumer<FeedbackPackage> check;
        private final Function<FeedbackPackage, PreparedPackage> publish;
        private final Map<String, FeedbackPackage> checked = new LinkedHashMap<>();
        private @Nullable String publishedId;
        private @Nullable PreparedPackage receipt;

        Drafts(UUID reviewId, Consumer<FeedbackPackage> check, Function<FeedbackPackage, PreparedPackage> publish) {
            this.reviewId = reviewId;
            this.check = check;
            this.publish = publish;
        }

        String check(FeedbackPackage proposal) {
            check.accept(proposal);
            String id = proposal.draftId(reviewId);
            checked.put(id, proposal);
            return id;
        }

        PreparedPackage publish(String id) {
            if (publishedId != null) {
                if (!publishedId.equals(id))
                    throw new IllegalArgumentException(
                            "This destination already accepted its package; do not publish another.");
                return Objects.requireNonNull(receipt);
            }
            var proposal = checked.get(id);
            if (proposal == null)
                throw new IllegalArgumentException(
                        "Unknown draft id; check the package first and use its returned draftId.");
            var accepted = publish.apply(proposal);
            publishedId = id;
            receipt = accepted;
            return accepted;
        }
    }

    private static List<UUID> distinct(List<Evidence.Ref> refs) {
        var ids = new LinkedHashSet<UUID>();
        refs.forEach(ref -> ids.add(UUID.fromString(ref.resultId())));
        return List.copyOf(ids);
    }

    private static List<Support> support(List<Evidence.Ref> refs) {
        return refs.stream()
                .map(ref -> new Support(
                        UUID.fromString(ref.resultId()),
                        ref.problemId() == null ? null : UUID.fromString(ref.problemId())))
                .toList();
    }
}
