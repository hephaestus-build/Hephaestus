package de.tum.cit.aet.hephaestus.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.conversation.ConversationAnswerUses.Outcome;
import de.tum.cit.aet.hephaestus.conversation.ConversationAnswerUses.ToolUse;
import de.tum.cit.aet.hephaestus.conversation.ConversationUsedSourceDTO.ContextDocument;
import de.tum.cit.aet.hephaestus.conversation.ConversationUsedSourceDTO.Kind;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What Hephaestus used counts sources in the person's terms, from the calls that the turn stored, and names only the
 * context documents (ADR 0157 decision 8).
 */
@Tag("unit")
class ConversationAnswerUsesTest {

    @Test
    void shouldCountDistinctSourcesAndNameNoPathRepositoryOrTool() {
        var uses = List.of(
                new ToolUse("1", "read", "/workspace/work/inputs/context/results_history.json"),
                new ToolUse("2", "read", "inputs/context/results_history.json"),
                new ToolUse("3", "load_context", "repos/github.com/acme/web@main"),
                new ToolUse("4", "read", "repos/github.com/acme/web/src/Retry.java"),
                new ToolUse("5", "read", "./repos/github.com/acme/web/src/Retry.java"),
                new ToolUse("6", "read", "repos/github.com/acme/web/src/RetryTest.java"),
                new ToolUse("7", "bash", null),
                new ToolUse("8", "search_conversation", null),
                new ToolUse("9", "search_conversation", null));
        var outcomes = uses.stream()
                .map(use -> new Outcome(use.callId(), use.tool(), false))
                .toList();

        var used = ConversationAnswerUses.of(true, uses, outcomes, true, true);

        assertThat(used.recorded()).isTrue();
        assertThat(used.read())
                .containsExactly(
                        new ConversationUsedSourceDTO(Kind.CONTEXT_DOCUMENT, ContextDocument.RESULTS_HISTORY, 1),
                        new ConversationUsedSourceDTO(Kind.REPOSITORY, null, 1),
                        new ConversationUsedSourceDTO(Kind.FILES, null, 2),
                        new ConversationUsedSourceDTO(Kind.COMMAND, null, 1),
                        new ConversationUsedSourceDTO(Kind.CONVERSATION_SEARCH, null, 2));
        assertThat(used.couldNotCheck()).isEmpty();
        assertThat(used.cited()).isTrue();
        assertThat(used.askedFromPage()).isTrue();
        assertThat(used.toString())
                .doesNotContain("acme")
                .doesNotContain("repos/")
                .doesNotContain("Retry");
    }

    @Test
    void shouldCountOneFileWhenAnInteriorPathSegmentNamesTheSameFile() {
        var uses = List.of(new ToolUse("1", "read", "INDEX.md"), new ToolUse("2", "read", "inputs/../INDEX.md"));
        var outcomes = List.of(new Outcome("1", "read", false), new Outcome("2", "read", false));

        var used = ConversationAnswerUses.of(true, uses, outcomes, false, false);

        assertThat(used.read()).containsExactly(new ConversationUsedSourceDTO(Kind.FILES, null, 1));
    }

    @Test
    void shouldKeepAFailedReadOfADocumentWithNoKnownName() {
        var uses = List.of(new ToolUse("read", "read", "inputs/context/missing.json"));

        var used = ConversationAnswerUses.of(true, uses, List.of(new Outcome("read", "read", true)), false, false);

        assertThat(used.read()).isEmpty();
        assertThat(used.couldNotCheck()).containsExactly(new ConversationUsedSourceDTO(Kind.CONTEXT_DOCUMENT, null, 1));
    }

    @Test
    void shouldListAFailedCallAndACallWithNoStoredResultUnderCouldNotCheck() {
        var uses = List.of(
                new ToolUse("1", "load_context", "repos/github.com/acme/api"),
                new ToolUse("2", "read", "inputs/context/merge_readiness.json"),
                new ToolUse("3", "search_conversation", null));

        var used = ConversationAnswerUses.of(
                true,
                uses,
                List.of(new Outcome("1", "load_context", true), new Outcome("2", "read", false)),
                false,
                false);

        assertThat(used.read())
                .containsExactly(
                        new ConversationUsedSourceDTO(Kind.CONTEXT_DOCUMENT, ContextDocument.MERGE_READINESS, 1));
        assertThat(used.couldNotCheck())
                .containsExactly(
                        new ConversationUsedSourceDTO(Kind.REPOSITORY, null, 1),
                        new ConversationUsedSourceDTO(Kind.CONVERSATION_SEARCH, null, 1));
    }

    @Test
    void shouldPairAnOutcomeWithItsCallByIdAndTool() {
        var uses = List.of(new ToolUse("1", "read", "inputs/context/user.json"));

        var used = ConversationAnswerUses.of(true, uses, List.of(new Outcome("1", "bash", false)), false, false);

        assertThat(used.read()).isEmpty();
        assertThat(used.couldNotCheck())
                .containsExactly(new ConversationUsedSourceDTO(Kind.CONTEXT_DOCUMENT, ContextDocument.USER, 1));
    }

    @Test
    void shouldSayNothingWasRecordedForATurnThatStoredNoEntries() {
        var used = ConversationAnswerUses.of(
                false, List.of(new ToolUse("1", "read", "inputs/context/user.json")), List.of(), true, false);

        assertThat(used.recorded()).isFalse();
        assertThat(used.read()).isEmpty();
        assertThat(used.couldNotCheck()).isEmpty();
        assertThat(used.cited()).isTrue();
    }

    @Test
    void shouldMapEachContextDocumentFileBothWays() {
        for (ContextDocument document : ContextDocument.values()) {
            assertThat(ContextDocument.ofFile(document.file())).contains(document);
        }
        assertThat(ContextDocument.ofFile("INDEX.md")).isEmpty();
        assertThat(ContextDocument.ofFile("unknown.json")).isEmpty();
    }
}
