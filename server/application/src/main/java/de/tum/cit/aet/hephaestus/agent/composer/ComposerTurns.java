package de.tum.cit.aet.hephaestus.agent.composer;

import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionTrace;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * What a composition's sessions render, as pure functions of the retained inputs: the files its turns read, the first
 * turn of a private destination, the legacy review on the work's turn ({@link OnWorkTurn} renders it), and the prepared
 * review on the work that private feedback follows.
 */
final class ComposerTurns {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    /** Reads the retained files, which are plain JSON: no application module changes how they parse. */
    private static final JsonMapper MAPPER = new JsonMapper();

    private ComposerTurns() {}

    /** The files a composition renders its turns from: the retained inputs, the request and the page support. */
    static ComposerFiles workspace(Map<String, byte[]> inputs, byte[] requestBytes, Admission state) {
        var files = new ComposerFiles(inputs);
        files.put(SandboxLayout.FEEDBACK_COMPOSITION_PATH, requestBytes);
        if (state.complete("PRACTICE_PAGE") && state.practicePageSupport() != null)
            files.put(ComposerFiles.SUPPORT, utf8(JsonText.stringify(state.practicePageSupport(), 2)));
        return files;
    }

    static List<String> practiceSlugs(JsonNode practiceIndex) {
        var slugs = new TreeSet<String>();
        Js.listOf(practiceIndex).forEach(practice -> slugs.add(Evidence.text(practice.path("slug"))));
        return List.copyOf(slugs);
    }

    /** The practices the review took on and admitted no result for: not settled, so nothing is said about them. */
    static List<String> notReached(JsonNode practiceIndex, Admission state) {
        var admitted = new LinkedHashSet<String>();
        state.results().forEach(result -> admitted.add(Evidence.text(result.path("practiceSlug"))));
        return Js.listOf(practiceIndex).stream()
                .map(practice -> Evidence.text(practice.path("slug")))
                .filter(slug -> !admitted.contains(slug))
                .toList();
    }

    /** The legacy review on the work: its turn from the admission's results and the request's copy forms. */
    static ReviewCompositionTurn reviewTurn(
            ComposerFiles files,
            CapturedWork captured,
            JsonNode folderIndex,
            JsonNode practiceIndex,
            Admission state,
            PrivateFeedback.Request request,
            JsonNode read,
            List<String> notReached,
            PrimarySource.Reader reader) {
        return OnWorkTurn.render(
                files,
                captured,
                folderIndex,
                practiceIndex,
                state.results(),
                request.onWorkCopyForms,
                read,
                notReached,
                reader);
    }

    /** The private turn includes proven primary source for its routed concerns, as the public turn does. */
    static String privateTurn(
            ComposerFiles files,
            CapturedWork captured,
            PrivateFeedback.Request request,
            Admission state,
            ReviewOnWork.@Nullable ComposedReview review,
            Map<String, List<String>> routes,
            List<String> notReached) {
        String brief = captured.brief();
        JsonNode folderIndex = files.json(SandboxLayout.MANIFEST_PATH, MAPPER).orElse(JSON.missingNode());
        var composable = PrivateFeedback.composable(state, routes);
        var eligible = state.results().stream()
                .filter(result -> {
                    String slug = result.path("practiceSlug").asString();
                    return composable.contains(slug)
                            && request.openPrivate().stream()
                                    .anyMatch(destination ->
                                            routes.getOrDefault(slug, List.of()).contains(destination));
                })
                .toList();
        String primary = PrimarySource.reference(
                files,
                ComposerFiles.CONTEXT_ROOT,
                ComposerFiles.REPOSITORY_ROOT,
                folderIndex,
                new ArrayList<>(eligible),
                PrimarySource.captured(files, folderIndex, ComposerFiles.CONTEXT_ROOT, ComposerFiles.REPOSITORY_ROOT));
        return String.join(
                "\n\n",
                List.of(brief, primary, PrivateFeedback.turn(files, MAPPER, request, state, review, routes, notReached))
                        .stream()
                        .filter(part -> !part.isEmpty())
                        .toList());
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** A review on the work that an attempt prepared, as a composition holds one it composed. */
    static ReviewOnWork.ComposedReview review(ResultAdmissionService.PreparedReview prepared) {
        String summary = prepared.summary();
        return new ReviewOnWork.ComposedReview(
                summary == null ? null : new ReviewOnWork.Summary(summary, evidence(prepared.summaryBasis())),
                prepared.notes().stream()
                        .map(note -> new ReviewOnWork.InlineNote(
                                note.body(),
                                evidence(note.basedOn()),
                                new ReviewOnWork.Anchor(
                                        note.anchor().problemId().toString(),
                                        note.anchor().citationIndex())))
                        .toList(),
                prepared.withheld().stream()
                        .map(decision -> new ReviewOnWork.Withheld(
                                evidence(decision.basedOn()), decision.reason(), decision.witnessIds()))
                        .toList());
    }

    private static List<Evidence.Ref> evidence(List<FeedbackCompositionTrace.Support> support) {
        return support.stream()
                .map(ref -> new Evidence.Ref(
                        ref.resultId().toString(),
                        ref.problemId() == null ? null : ref.problemId().toString()))
                .toList();
    }
}
