package de.tum.cit.aet.hephaestus.agent.composer;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.runtime.FeedbackWritingGuide;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ComposerPromptsTest {

    @Test
    void sharedStyleIncludesTheWritingGuide() {
        assertThat(ComposerPrompts.sharedStyle()).endsWith(FeedbackWritingGuide.text());
    }
}
