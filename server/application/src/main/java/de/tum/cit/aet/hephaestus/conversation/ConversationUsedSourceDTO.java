package de.tum.cit.aet.hephaestus.conversation;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Sources of one kind that an answer's turn opened, or tried to open, counted in the person's terms. It never names a
 * tool, a path or a repository, because a name could outlive the person's access to it.
 */
@Schema(description = "Sources of one kind that Hephaestus opened for an answer, or could not open")
public record ConversationUsedSourceDTO(
        @NonNull Kind kind,

        @Schema(description = "Which context document; only for CONTEXT_DOCUMENT, absent for one with no known name")
        @Nullable
        ContextDocument document,

        @Schema(
                description = "How many distinct repositories, files, commands or searches; 1 for a document",
                requiredMode = RequiredMode.REQUIRED)
        int count) {

    /** What kind of source it is. */
    @Schema(name = "ConversationUsedSourceKind")
    public enum Kind {
        /** One of the documents that Hephaestus prepares about the person's work. */
        CONTEXT_DOCUMENT,
        /** Repositories that the turn loaded. */
        REPOSITORY,
        /** Files that the turn read, other than context documents. */
        FILES,
        /** Shell commands that the turn ran over the files it had. */
        COMMAND,
        /** Searches of the earlier messages of this conversation. */
        CONVERSATION_SEARCH,
    }

    /**
     * The documents that Hephaestus prepares for a turn, by file name. {@code ContextDocumentCoverageTest} fails when the
     * context folder writes a document that has no constant here.
     */
    @Schema(name = "ConversationContextDocument")
    public enum ContextDocument {
        USER,
        WORKSPACE,
        RECENT_AUTHORED_WORK,
        ACTIVITY,
        MERGE_READINESS,
        REVIEW_ATTEMPTS,
        RESULTS_HISTORY,
        DELIVERED_FEEDBACK,
        PREPARED_CONVERSATION_FEEDBACK,
        PRACTICE_PROFILE,
        PRACTICE_CATALOG,
        SLACK_CONVERSATIONS,
        OUTLINE_DOCS,
        WEEKLY_DIGEST;

        /** The document of a file name such as {@code results_history.json}, if it is one. */
        public static Optional<ContextDocument> ofFile(String file) {
            if (!file.endsWith(".json")) return Optional.empty();
            String name = file.substring(0, file.length() - ".json".length()).toUpperCase(Locale.ROOT);
            for (ContextDocument document : values()) {
                if (document.name().equals(name)) return Optional.of(document);
            }
            return Optional.empty();
        }

        /** The file name that the context folder writes. */
        public String file() {
            return name().toLowerCase(Locale.ROOT) + ".json";
        }
    }
}
