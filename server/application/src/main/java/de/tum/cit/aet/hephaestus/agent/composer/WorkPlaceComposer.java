package de.tum.cit.aet.hephaestus.agent.composer;

import de.tum.cit.aet.hephaestus.agent.handler.FeedbackTextPolicy;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The server side of the work-place composer (ADR 0121): the review on the work rendered from one work-place handoff,
 * and the check of a draft against it. It reads the handoff and the public history of Hephaestus's own copies on the
 * work, and nothing else: no admission, no private read, no conversation and no personal record.
 *
 * <p>It runs in shadow. Its one tool is {@code check_package}, which returns a checked draft or its refusal and stores
 * nothing. The check applies the text policy of every destination with links that only the handoff's quotes supply. It has no tool that publishes, edits or replies, so its drafts cannot reach the work.
 * {@code WorkPlaceComposerBoundaryArchTest} keeps both true.
 */
final class WorkPlaceComposer {
    private static final JsonMapper MAPPER = new JsonMapper();
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    static final String CHECK_PACKAGE = "check_package";

    /** The legacy prompt names the publication tools; a shadow session has none, and says so. */
    static final String SHADOW_NOTE =
            "\nThis is a shadow composition. Only check_package exists. Check the complete draft"
                    + " and stop. Nothing is published.\n";

    private WorkPlaceComposer() {}

    record Tool(String name, String description, ObjectNode parameters) {}

    /**
     * One session of the review on the work.
     *
     * @param turn the admitted facts the check reads: the restable results, the witnesses and the copy forms
     * @param quotes the quotes the handoff's results cite, the only ones that may supply a link
     */
    record Session(String system, String input, List<Tool> tools, ReviewCompositionTurn turn, List<String> quotes) {
        Session {
            tools = List.copyOf(tools);
            quotes = List.copyOf(quotes);
        }
    }

    /**
     * What a check found: the checked draft, or the reasons it was refused. A refused draft is never kept.
     *
     * @param draft the review on the work the draft composes, or null when it was refused
     */
    record Check(ReviewOnWork.@Nullable ComposedReview draft, List<String> errors) {
        Check {
            errors = List.copyOf(errors);
        }
    }

    /**
     * The session of a handoff, read against the public history of the work.
     *
     * @param publicHistory Hephaestus's own earlier copies on this work, as the public history read returns them
     */
    static Session session(WorkPlaceHandoff handoff, JsonNode publicHistory) {
        var files = new ComposerFiles(handoff.files());
        JsonNode folderIndex = files.json(SandboxLayout.MANIFEST_PATH, MAPPER).orElse(JSON.missingNode());
        JsonNode practiceIndex =
                files.json(ComposerFiles.PRACTICE_INDEX, MAPPER).orElse(JSON.arrayNode());
        var turn = OnWorkTurn.render(
                files,
                new CapturedWork(files, MAPPER),
                folderIndex,
                practiceIndex,
                handoff.results(),
                handoff.onWorkCopyForms(),
                publicHistory,
                handoff.notReached(),
                PrimarySource.captured(files, folderIndex, ComposerFiles.CONTEXT_ROOT, ComposerFiles.REPOSITORY_ROOT));
        return new Session(
                ComposerPrompts.reviewSystem(),
                turn.text() + SHADOW_NOTE,
                List.of(new Tool(CHECK_PACKAGE, ReviewOnWork.TOOL_DESCRIPTION, turn.toolParameters())),
                turn,
                WorkPlaceScope.quotes(handoff));
    }

    /**
     * Checks a {@code check_package} draft: its decisions and support against the session's admitted facts, then the
     * text policy of every piece, with links supplied only by the handoff's own quotes. Nothing is stored.
     */
    static Check check(Session session, JsonNode arguments) {
        var turn = session.turn();
        var read = ReviewOnWork.read(arguments, turn.restable(), turn.lineNotes(), turn.witnesses());
        var review = read.review();
        if (review == null) return new Check(null, read.errors());
        var policy = FeedbackTextPolicy.suppliedBy(session.quotes());
        for (var piece : CompositionPackages.review(review, null).composition()) {
            var refusal = policy.refusal(piece);
            if (refusal != null) return new Check(null, List.of(refusal.rule().name() + " in " + refusal.field()));
        }
        return new Check(review, List.of());
    }
}
