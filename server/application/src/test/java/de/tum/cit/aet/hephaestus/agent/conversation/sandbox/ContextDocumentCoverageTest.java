package de.tum.cit.aet.hephaestus.agent.conversation.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.conversation.ConversationUsedSourceDTO.ContextDocument;
import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** What Hephaestus used can name every document that the context folder writes, and no other. */
@Tag("unit")
class ContextDocumentCoverageTest {

    @Test
    void shouldNameEveryContextDocumentOfTheFolder() {
        assertThat(ContextFolderIndex.DOCUMENTS.keySet())
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(ContextDocument.values())
                        .map(ContextDocument::file)
                        .toList());
    }
}
