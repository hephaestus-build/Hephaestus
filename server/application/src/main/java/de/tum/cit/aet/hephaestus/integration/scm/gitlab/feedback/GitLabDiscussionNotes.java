package de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback;

import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyForm;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyUpdateKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyUpdateReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.core.spi.LineNote;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmVersion;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFormatter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Reads GitLab's notes, discussions and diff positions, builds note paths, and builds the receipts of an edited or
 * unsupported note, for {@link GitLabLineNoteWriter} and {@link GitLabThreadResolutionWriter}, without a provider call.
 */
final class GitLabDiscussionNotes {

    static final String DISCUSSION_GID = "gid://gitlab/Discussion/";

    static final Pattern SEGMENT = Pattern.compile("[0-9A-Za-z]{1,128}");

    // The note models a feedback copy can have been recorded as, each with its REST id.
    static final Pattern NOTE_REF = Pattern.compile("gid://gitlab/(Note|DiscussionNote|DiffNote)/([1-9][0-9]{0,17})");

    // A generic 404 proves absence only when the merge request is still readable.
    static final Pattern NOTE_ABSENT = Pattern.compile("\\{\\s*\"message\"\\s*:\\s*\"404 Not found\"\\s*}");

    private GitLabDiscussionNotes() {}

    static boolean sameDraftPosition(JsonNode actual, @Nullable Object expected) {
        // GitLab answers a draft without a position with a position whose type is "text" and whose location is null.
        if (expected == null)
            return actual.isNull()
                    || actual.isMissingNode()
                    || (actual.isObject()
                            && actual.properties().stream()
                                    .allMatch(entry -> entry.getValue().isNull()
                                            || entry.getKey().equals("position_type")));
        var wanted = GitLabNotesApi.MAPPER.valueToTree(expected);
        return wanted.properties().stream().allMatch(entry -> entry.getValue().equals(actual.path(entry.getKey())));
    }

    static @Nullable CopyForm copyForm(Map<?, ?> note, String body, LineNote item, FeedbackTarget target) {
        ItemVersion version = target.version();
        FeedbackAnchor diff = item.anchor();
        String revision = diff.side() == CitationSide.OLD ? version.baseVersion() : ScmVersion.reviewedHead(version);
        if (diff.endLine() != null && diff.side() == null) return null;
        // Only a position the response answered with null proves an ordinary comment; a missing one
        // proves nothing.
        boolean unpositioned = note.containsKey("position") && note.get("position") == null;
        if (unpositioned && ScmFeedbackFormatter.carries(body, item.writeMarker())) {
            return CopyForm.FILE_NOTE;
        }
        boolean lineCopy = diff.endLine() != null
                && revision != null
                && ScmFeedbackFormatter.carries(body, item.writeMarker())
                && note.get("position") instanceof Map<?, ?> position
                && diff.path().equals(position.get(diff.side() == CitationSide.OLD ? "oldPath" : "newPath"))
                && position.get(diff.side() == CitationSide.OLD ? "oldLine" : "newLine") instanceof Number line
                && line.intValue() == diff.endLine()
                && position.get("diffRefs") instanceof Map<?, ?> diffRefs
                && Objects.equals(version.baseVersion(), diffRefs.get("baseSha"))
                && Objects.equals(ScmVersion.reviewedHead(version), diffRefs.get("headSha"))
                && diffRefs.get("startSha") instanceof String startSha
                && GitLabLineNoteWriter.COMMIT_SHA.matcher(startSha).matches();
        return lineCopy ? CopyForm.LINE_NOTE : null;
    }

    static boolean placesRange(String body, LineNote item) {
        return ranged(item.anchor())
                && ScmFeedbackFormatter.carries(body, GitLabLineNoteWriter.RANGE_PLACEMENT)
                && ScmFeedbackFormatter.carries(body, item.writeMarker());
    }

    static boolean ranged(FeedbackAnchor anchor) {
        Integer start = anchor.startLine();
        Integer end = anchor.endLine();
        return start != null && end != null && start < end;
    }

    static String encodedPath(String path) {
        return Arrays.stream(path.split("/", -1))
                .map(segment ->
                        URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("/"));
    }

    // A reply can turn a plain Note into a DiscussionNote without changing its identity or author.
    static boolean ownNote(JsonNode note, long id, String type, long author) {
        return note.path("id").isIntegralNumber()
                && note.path("id").longValue() == id
                && ("Note".equals(type)
                        ? note.path("type").isNull() || "DiscussionNote".equals(text(note, "type"))
                        : type.equals(text(note, "type")))
                && note.path("system").isBoolean()
                && !note.path("system").booleanValue()
                && note.path("author").path("id").isIntegralNumber()
                && note.path("author").path("id").longValue() == author;
    }

    // The receipt keeps the identity and address the copy was delivered under.
    static CopyUpdateReceipt edited(CopyReceipt copy) {
        return new CopyUpdateReceipt(
                copy.copyId(), CopyUpdateKind.EDITED, copy.externalRef(), copy.externalUrl(), null);
    }

    static CopyUpdateReceipt unsupported(CopyReceipt copy, String reason) {
        return new CopyUpdateReceipt(
                copy.copyId(), CopyUpdateKind.UNSUPPORTED, copy.externalRef(), copy.externalUrl(), reason);
    }

    static @Nullable Integer line(JsonNode node, String field) {
        return node.path(field).isIntegralNumber() ? node.path(field).intValue() : null;
    }

    static @Nullable String text(JsonNode node, String field) {
        return node.path(field).isString() ? node.path(field).asString() : null;
    }
}
