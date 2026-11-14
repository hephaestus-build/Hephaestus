package de.tum.cit.aet.hephaestus.agent.conversation.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.runtime.PiRuntimeFactory;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ContextFolderPathsTest extends BaseUnitTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "inputs/context/user.json, inputs/context/user.json",
        "/workspace/work/INDEX.md, INDEX.md",
        "./repos//github.com/acme/api/./README.md, repos/github.com/acme/api/README.md",
        "repos/github.com/acme/../acme/api, repos/github.com/acme/api",
    })
    void shouldNameThePathInTheFolderWhenItStaysInside(String path, String relative) {
        assertThat(ContextFolderPaths.relative(path)).contains(relative);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "../etc/passwd",
                "inputs/../../etc/passwd",
                "/etc/passwd",
                "/workspace/work/../etc/passwd",
                "/workspace/workx/INDEX.md",
                "/workspace/INDEX.md",
                "~/.ssh/id_rsa",
                "/proc/1/environ",
                "inputs/\0x"
            })
    void shouldNameNoPathWhenItLeavesTheFolder(String path) {
        assertThat(ContextFolderPaths.relative(path)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"INDEX.md", "FIELDS.md", "inputs/context/user.json", "inputs/context/issue/7.json"})
    void shouldServeTheIndexTheFieldGuideAndTheContextDocumentsFromMemory(String relative) {
        assertThat(ContextFolderPaths.document(relative)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"repos/github.com/acme/api/README.md", "inputs/other.json", "notes.txt", ""})
    void shouldExcludePathsOutsideTheContextDocuments(String relative) {
        assertThat(ContextFolderPaths.document(relative)).isFalse();
    }

    @Test
    void shouldNameTheFolderInTheSystemPromptWhereTheToolsFindIt() {
        String prompt =
                new String(PiRuntimeFactory.loadClasspathResource("conversation/system.md"), StandardCharsets.UTF_8);

        assertThat(prompt).contains("context folder at `" + ContextFolderPaths.ROOT + "`");
    }
}
