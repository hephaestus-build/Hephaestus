package de.tum.cit.aet.hephaestus.agent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TaskPathsTest extends BaseUnitTest {
    @Test
    void shouldAcceptRelocatedInputsWithoutRequiringACapturedInputPrefix() {
        var paths = new TaskPaths(
                "areas/scm",
                "repos/project",
                "manifest.json",
                "catalog/index.json",
                "composition.json",
                "history/prepared.json",
                "scripts/practices");
        assertThat(paths.contextRoot()).isEqualTo("areas/scm");
        assertThat(paths.repositoryRoot()).isEqualTo("repos/project");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                " ",
                "\u00a0",
                "\u2007",
                "\u202f",
                "/etc/passwd",
                "../secret",
                "area/../secret",
                "area/./file",
                "area//file",
                "area/",
                "C:/secret",
                "area\\file",
                "area\nfile",
                "area\u0085file"
            })
    void shouldRejectUnsafeOrNonNormalizedPaths(String path) {
        assertThatThrownBy(() -> new TaskPaths(path, "repo", "manifest", "index", "composition", "prepared", "scripts"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspace-relative");
    }
}
