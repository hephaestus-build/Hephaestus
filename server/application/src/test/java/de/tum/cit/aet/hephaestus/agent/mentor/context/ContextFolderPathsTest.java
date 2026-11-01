package de.tum.cit.aet.hephaestus.agent.mentor.context;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ContextFolderPathsTest extends BaseUnitTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "inputs/context/user.json, /workspace/inputs/context/user.json",
        "/workspace/INDEX.md, /workspace/INDEX.md",
        "./repos//github.com/acme/api/./README.md, /workspace/repos/github.com/acme/api/README.md",
        "repos/github.com/acme/../acme/api, /workspace/repos/github.com/acme/api",
        "/workspace, /workspace",
        "/tmp/heph-out/12.log, /tmp/heph-out/12.log"
    })
    void shouldReadThePathInTheFolderWhenItStaysInside(String path, String readable) {
        assertThat(ContextFolderPaths.readable(path)).contains(readable);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "../etc/passwd",
                "inputs/../../etc/passwd",
                "/etc/passwd",
                "/workspace/../etc/passwd",
                "/workspacex/INDEX.md",
                "~/.ssh/id_rsa",
                "/tmp/heph-out/../../etc/passwd",
                "/tmp/other.txt",
                "/proc/1/environ",
                "inputs/\0x"
            })
    void shouldNameNoFileWhenThePathLeavesTheFolder(String path) {
        assertThat(ContextFolderPaths.readable(path)).isEmpty();
    }
}
