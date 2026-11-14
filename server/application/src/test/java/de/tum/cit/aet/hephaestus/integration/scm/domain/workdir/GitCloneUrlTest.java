package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GitCloneUrlTest extends BaseUnitTest {

    @Test
    void shouldBuildTheCloneUrlOfANestedPath() {
        assertThat(GitCloneUrl.of("https://gitlab.example.com", "group/sub/repo"))
                .contains("https://gitlab.example.com/group/sub/repo.git");
        assertThat(GitCloneUrl.require("https://github.com", "owner/repo"))
                .isEqualTo("https://github.com/owner/repo.git");
    }

    @Test
    void shouldBuildOneSlashAfterAServerUrlThatEndsInOne() {
        assertThat(GitCloneUrl.of("https://gitlab.example.com/", "group/repo"))
                .contains("https://gitlab.example.com/group/repo.git");
    }

    @ParameterizedTest
    @ValueSource(strings = {"repo", "group/../other", "./repo", "group//repo", "group/ /repo", "group/repo/.."})
    void shouldRefuseAPathThatIsNotAProviderPath(String path) {
        assertThat(GitCloneUrl.of("https://gitlab.example.com", path)).isEmpty();
        assertThatThrownBy(() -> GitCloneUrl.require("https://gitlab.example.com", path))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
