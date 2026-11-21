package de.tum.cit.aet.hephaestus.integration.scm.context;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** The JSON writers that the work context sources share: clipped text, and a comment with its author's relation. */
final class ScmContextJson {

    private static final int MAX_BODY_CHARS = 1_000;

    private ScmContextJson() {}

    /** @return whether the body was clipped */
    static boolean putComment(
            ArrayNode into,
            @Nullable User workAuthor,
            @Nullable User author,
            String body,
            @Nullable Instant createdAt,
            @Nullable String url) {
        ObjectNode c = into.addObject();
        c.put("author", author == null ? null : author.getLogin());
        c.put("authorRelation", authorRelation(workAuthor, author));
        if (author != null && author.getType() == User.Type.BOT) {
            c.put("bot", true);
        }
        c.put("createdAt", createdAt == null ? null : createdAt.toString());
        c.put("url", url);
        // Not in the ledger yet carrying the marker: Hephaestus's note under an unmatched ref, or a person quoting one.
        if (body.contains(WorkspaceScmProjection.HEPHAESTUS_MARKER)) {
            c.put("quotesHephaestusMarker", true);
        }
        return putBody(c, body);
    }

    /** Whether {@code author} wrote the work itself, by stored identity; unknown when either identity is missing. */
    private static String authorRelation(@Nullable User workAuthor, @Nullable User author) {
        Long workAuthorId = workAuthor == null ? null : workAuthor.getId();
        Long authorId = author == null ? null : author.getId();
        if (workAuthorId == null || authorId == null) {
            return "UNKNOWN";
        }
        return workAuthorId.equals(authorId) ? "WORK_AUTHOR" : "OTHER_PARTICIPANT";
    }

    /** @return whether the body was clipped — a condition past the cut is then unseen */
    static boolean putBody(ObjectNode entry, String body) {
        return putText(entry, "body", body);
    }

    static boolean putText(ObjectNode entry, String key, String text) {
        return putText(entry, key, text, MAX_BODY_CHARS);
    }

    /**
     * Puts {@code text} under {@code key}, clipped at {@code max} without splitting a surrogate pair, and marks a
     * clipped one with {@code <key>Truncated}.
     *
     * @return whether the text was clipped — a condition past the cut is then unseen
     */
    static boolean putText(ObjectNode entry, String key, String text, int max) {
        if (text.length() <= max) {
            entry.put(key, text);
            return false;
        }
        int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        entry.put(key, text.substring(0, end));
        entry.put(key + "Truncated", true);
        return true;
    }
}
