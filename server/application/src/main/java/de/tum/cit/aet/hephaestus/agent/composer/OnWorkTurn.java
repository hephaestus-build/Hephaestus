package de.tum.cit.aet.hephaestus.agent.composer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The first turn of the review on the work, as a pure function of the work's captured record and its public results.
 * The legacy composition and the work-place composer render it alike. It takes no admission, request or private read:
 * every result it speaks about passes {@link ReviewOnWork#publicNotMet} or is a public qualification, and the history
 * it reads is Hephaestus's own earlier copies on this work.
 */
final class OnWorkTurn {
    /** Reads the retained files, which are plain JSON: no application module changes how they parse. */
    private static final JsonMapper MAPPER = new JsonMapper();

    private OnWorkTurn() {}

    /**
     * @param results the admitted results; only the public ones shape the turn
     * @param onWorkCopyForms the copy forms the work takes, from the composition request
     * @param read the public history read: Hephaestus's own earlier copies on this work and the read's time
     * @param notReached the practices the review took on with no result to speak about
     * @param reader the reader of the captured texts and the captured change
     */
    static ReviewCompositionTurn render(
            ComposerFiles files,
            CapturedWork captured,
            JsonNode folderIndex,
            JsonNode practiceIndex,
            List<ObjectNode> results,
            List<String> onWorkCopyForms,
            JsonNode read,
            List<String> notReached,
            PrimarySource.Reader reader) {
        boolean lineNotes = onWorkCopyForms.contains("LINE_NOTE");
        var framing = new CapturedWork.Framing(null, null);
        var reviewable = ReviewOnWork.publicResults(results);
        String readAt = Js.string(read.path("readAt"));
        ObjectNode history = captured.publicReviewHistory(folderIndex, framing);
        var publicHistory = ReviewOnWork.publicHistory(read);
        String work = thisWork(files, folderIndex);
        var alreadySaid =
                ReviewOnWork.priorPublicFeedback(publicHistory, work, Js.string(history.path("capturedAt")), readAt);
        var witnesses = ReviewOnWork.priorAdviceWitnesses(alreadySaid.feedback(), history.path("statements"));
        var eligible = witnesses.entrySet().stream()
                .filter(entry -> entry.getValue().eligibleForAlreadySaid())
                .map(Map.Entry::getKey)
                .toList();
        String primary = PrimarySource.reference(
                files,
                ComposerFiles.CONTEXT_ROOT,
                ComposerFiles.REPOSITORY_ROOT,
                folderIndex,
                new ArrayList<>(
                        results.stream().filter(ReviewOnWork::publicNotMet).toList()),
                reader);
        String sameWork = String.join(
                "\n\n",
                List.of(captured.sameWorkContext(folderIndex, framing), primary).stream()
                        .filter(part -> !part.isEmpty())
                        .toList());
        String text = ReviewOnWork.turn(new ReviewOnWork.TurnInput(
                sameWork,
                reviewable,
                ReviewOnWork.publicQualifications(results),
                alreadySaid,
                readAt,
                history,
                practices(practiceIndex, reviewable),
                notReached,
                lineNotes,
                slug -> files.text(ComposerFiles.criteria(slug)).orElse(null)));
        var restable = ReviewOnWork.restable(results);
        return new ReviewCompositionTurn(
                text,
                ReviewOnWork.toolParameters(restable, lineNotes, eligible),
                restable,
                lineNotes,
                witnesses,
                ReviewOnWork.earlierNotes(publicHistory, work),
                Js.string(read.path("publicationContext")));
    }

    /** The reviewed work's identity, derived from the captured record. */
    static @Nullable String thisWork(ComposerFiles files, JsonNode folderIndex) {
        var metadata = files.json(ComposerFiles.CONTEXT_ROOT + "/metadata.json", MAPPER)
                .orElse(null);
        if (metadata == null || !folderIndex.isObject() || !metadata.isObject()) return null;
        String kind = Js.string(folderIndex.path("itemKind"));
        boolean pullRequest = "scm.pull_request".equals(kind);
        if (!pullRequest && !"scm.issue".equals(kind)) return null;
        return ReviewOnWork.workIdentity(
                folderIndex.path("itemKind"), metadata.path(pullRequest ? "pr_url" : "html_url"));
    }

    /** The practices of the reviewable results, as the practice index names them. */
    static List<ReviewOnWork.Practice> practices(JsonNode practiceIndex, List<ObjectNode> reviewable) {
        var slugs = new LinkedHashSet<String>();
        reviewable.forEach(result -> slugs.add(Evidence.text(result.path("practiceSlug"))));
        var practices = new ArrayList<ReviewOnWork.Practice>();
        for (JsonNode practice : Js.listOf(practiceIndex)) {
            String slug = Js.string(practice.path("slug"));
            if (slug == null || !slugs.contains(slug)) continue;
            practices.add(new ReviewOnWork.Practice(
                    slug,
                    Evidence.text(practice.path("name")),
                    Js.string(practice.path("whyItMatters")),
                    Js.listOf(practice.path("knownLimitations")).stream()
                            .map(JsonNode::asString)
                            .toList(),
                    Js.string(practice.path("versionId"))));
        }
        return practices;
    }
}
