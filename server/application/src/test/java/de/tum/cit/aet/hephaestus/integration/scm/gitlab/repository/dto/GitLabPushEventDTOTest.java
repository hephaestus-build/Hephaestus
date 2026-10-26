package de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class GitLabPushEventDTOTest extends BaseUnitTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Nested
    class JsonDeserialization {

        @Test
        void deserializesRealFixture() throws IOException {
            var resource = new ClassPathResource("gitlab/push.json");
            GitLabPushEventDTO dto = objectMapper.readValue(resource.getInputStream(), GitLabPushEventDTO.class);

            assertThat(dto.objectKind()).isEqualTo("push");
            assertThat(dto.ref()).isEqualTo("refs/heads/main");
            assertThat(dto.before()).isEqualTo("9c5dedd52046bb5213189afc25f75e608a98d462");
            assertThat(dto.after()).isEqualTo("a4bf10d93a2d136f1db911b6f1c03d26d835a44f");
            assertThat(dto.checkoutSha()).isEqualTo("a4bf10d93a2d136f1db911b6f1c03d26d835a44f");
            assertThat(dto.projectId()).isEqualTo(246765L);
            assertThat(dto.totalCommitsCount()).isEqualTo(3);

            // Commits
            assertThat(dto.commits()).isNotNull().hasSize(3);
            assertThat(dto.commits().get(0).id()).isEqualTo("9c5dedd52046bb5213189afc25f75e608a98d462");
            assertThat(dto.commits().get(0).message()).isEqualTo("Initial commit");
            assertThat(dto.commits().get(0).author()).isNotNull();
            assertThat(dto.commits().get(0).author().name()).isEqualTo("Muster, Max (Erika)");
            assertThat(dto.commits().get(0).added()).containsExactly("README.md");
            assertThat(dto.commits().get(0).changedFilesCount()).isEqualTo(1);

            // Project info
            assertThat(dto.project()).isNotNull();
            assertThat(dto.project().id()).isEqualTo(246765L);
            assertNotNull(dto.project());
            assertThat(dto.project().name()).isEqualTo("demo-repository");
            assertThat(dto.project().pathWithNamespace()).isEqualTo("hephaestustest/demo-repository");
            assertThat(dto.project().webUrl()).isEqualTo("https://gitlab.lrz.de/hephaestustest/demo-repository");
            assertThat(dto.project().defaultBranch()).isEqualTo("main");
            assertThat(dto.project().visibilityLevel()).isZero();
        }

        @Test
        void unknownFieldsAreIgnored() throws IOException {
            String json = """
                {
                    "object_kind": "push",
                    "ref": "refs/heads/main",
                    "before": "abc",
                    "after": "def",
                    "project_id": 1,
                    "total_commits_count": 0,
                    "unknown_field": "should be ignored",
                    "project": {
                        "id": 1,
                        "name": "test",
                        "path_with_namespace": "org/test",
                        "web_url": "https://gitlab.com/org/test",
                        "visibility_level": 20,
                        "extra_nested_field": true
                    }
                }
                """;

            GitLabPushEventDTO dto = objectMapper.readValue(json, GitLabPushEventDTO.class);

            assertThat(dto.objectKind()).isEqualTo("push");
            assertNotNull(dto.project());
            assertThat(dto.project().name()).isEqualTo("test");
        }
    }

    @Nested
    class IsDefaultBranch {

        @Test
        void defaultBranch_returnsTrue() {
            var projectInfo = new GitLabPushEventDTO.ProjectInfo(1L, "proj", null, "url", null, "org/proj", "main", 0);
            var dto = new GitLabPushEventDTO("push", "refs/heads/main", "b", "a", null, 1L, projectInfo, 1, null);

            assertThat(dto.isDefaultBranch()).isTrue();
        }

        @Test
        void nonDefaultBranch_returnsFalse() {
            var projectInfo = new GitLabPushEventDTO.ProjectInfo(1L, "proj", null, "url", null, "org/proj", "main", 0);
            var dto = new GitLabPushEventDTO("push", "refs/heads/feature", "b", "a", null, 1L, projectInfo, 1, null);

            assertThat(dto.isDefaultBranch()).isFalse();
        }

        @Test
        void nullProject_returnsFalse() {
            var dto = new GitLabPushEventDTO("push", "refs/heads/main", "b", "a", null, 1L, null, 0, null);

            assertThat(dto.isDefaultBranch()).isFalse();
        }
    }

    @Nested
    class IsBranchDeletion {

        @Test
        void allZeroAfter_isBranchDeletion() {
            var dto = new GitLabPushEventDTO(
                    "push",
                    "refs/heads/feature",
                    "abc123",
                    "0000000000000000000000000000000000000000",
                    null,
                    1L,
                    null,
                    0,
                    null);

            assertThat(dto.isBranchDeletion()).isTrue();
        }

        @Test
        void nullAfter_isNotBranchDeletion() {
            var dto = new GitLabPushEventDTO("push", "refs/heads/main", "abc", null, null, 1L, null, 0, null);
            assertThat(dto.isBranchDeletion()).isFalse();
        }

        @Test
        void shortZeroString_isNotBranchDeletion() {
            var dto = new GitLabPushEventDTO("push", "refs/heads/main", "abc", "000", null, 1L, null, 0, null);
            assertThat(dto.isBranchDeletion()).isFalse();
        }

        @Test
        void nonZeroAfter_isNotBranchDeletion() {
            var dto = new GitLabPushEventDTO("push", "refs/heads/main", "abc123", "def456", null, 1L, null, 1, null);

            assertThat(dto.isBranchDeletion()).isFalse();
        }
    }
}
