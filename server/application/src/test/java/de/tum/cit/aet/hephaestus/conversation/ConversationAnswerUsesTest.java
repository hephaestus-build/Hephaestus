package de.tum.cit.aet.hephaestus.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.conversation.ConversationAnswerUses.Outcome;
import de.tum.cit.aet.hephaestus.conversation.ConversationAnswerUses.ToolUse;
import de.tum.cit.aet.hephaestus.conversation.ConversationUsedSourceDTO.ContextDocument;
import de.tum.cit.aet.hephaestus.conversation.ConversationUsedSourceDTO.Kind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** What Hephaestus used names sources in the person's terms, from the calls that finished (ADR 0157 decision 8). */
@Tag("unit")
class ConversationAnswerUsesTest {

    @Test
    void shouldNameDocumentsRepositoriesFilesAndSearchesWithoutAPathOrATool() {
        var uses = List.of(
                use("1", "read", Map.of("path", "/workspace/work/inputs/context/results_history.json")),
                use("2", "read", Map.of("path", "inputs/context/results_history.json")),
                use("3", "load_context", Map.of("path", "repositories/acme/web@main")),
                use("4", "read", Map.of("path", "repositories/acme/web/src/Retry.java")),
                use("5", "read", Map.of("path", "./repositories/acme/web/src/RetryTest.java")),
                use("6", "bash", Map.of("command", "grep -r retry inputs/context")),
                use("7", "search_conversation", Map.of("query", "retry")),
                use("8", "search_conversation", Map.of("query", "flaky")));
        var outcomes = List.of("1", "2", "3", "4", "5", "6", "7", "8").stream()
                .map(id -> new Outcome(id, "any", false))
                .toList();

        var used = ConversationAnswerUses.of(true, uses, outcomes);

        assertThat(used.recorded()).isTrue();
        assertThat(used.read())
                .containsExactly(
                        new ConversationUsedSourceDTO(Kind.CONTEXT_DOCUMENT, ContextDocument.RESULTS_HISTORY, null, 1),
                        new ConversationUsedSourceDTO(Kind.REPOSITORY, null, "repositories/acme/web", 1),
                        new ConversationUsedSourceDTO(Kind.REPOSITORY_FILES, null, "repositories/acme/web", 2),
                        new ConversationUsedSourceDTO(Kind.CONTEXT_SEARCH, null, null, 1),
                        new ConversationUsedSourceDTO(Kind.CONVERSATION_SEARCH, null, null, 2));
        assertThat(used.couldNotCheck()).isEmpty();
        assertThat(used.toString())
                .doesNotContain("Retry.java")
                .doesNotContain("grep")
                .doesNotContain("flaky");
    }

    @Test
    void shouldListAFailedCallUnderCouldNotCheckAndLeaveOutACallThatNeverFinished() {
        var uses = List.of(
                use("1", "load_context", Map.of("path", "repositories/acme/api")),
                use("2", "read", Map.of("path", "inputs/context/merge_readiness.json")),
                use("3", "search_conversation", Map.of("query", "deploy")));

        var used = ConversationAnswerUses.of(
                true, uses, List.of(new Outcome("1", "load_context", true), new Outcome("2", "read", false)));

        assertThat(used.couldNotCheck())
                .containsExactly(new ConversationUsedSourceDTO(Kind.REPOSITORY, null, "repositories/acme/api", 1));
        assertThat(used.read())
                .containsExactly(
                        new ConversationUsedSourceDTO(Kind.CONTEXT_DOCUMENT, ContextDocument.MERGE_READINESS, null, 1));
    }

    @Test
    void shouldSayNothingWasRecordedForATurnThatStoredNoEntries() {
        var used = ConversationAnswerUses.of(
                false, List.of(use("1", "read", Map.of("path", "inputs/context/user.json"))), List.of());

        assertThat(used.recorded()).isFalse();
        assertThat(used.read()).isEmpty();
        assertThat(used.couldNotCheck()).isEmpty();
    }

    @Test
    void shouldMapEachContextDocumentFileBothWays() {
        for (ContextDocument document : ContextDocument.values()) {
            assertThat(ContextDocument.ofFile(document.file())).contains(document);
        }
        assertThat(ContextDocument.ofFile("INDEX.md")).isEmpty();
        assertThat(ContextDocument.ofFile("unknown.json")).isEmpty();
    }

    private static ToolUse use(String id, String tool, Map<String, String> arguments) {
        return new ToolUse(id, tool, arguments.get("path"));
    }
}
