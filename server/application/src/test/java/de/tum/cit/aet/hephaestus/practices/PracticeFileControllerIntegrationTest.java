package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeFileCheckDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeFileCheckRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeFileProblemDTO;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

class PracticeFileControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    WebTestClient client;

    @Autowired
    PracticeVersionService versions;

    @Test
    @WithAdminUser
    void shouldDownloadThePracticeFolderAndCheckItBackAsValid() throws IOException {
        var owner = persistUser("admin");
        var workspace = createWorkspace("files-roundtrip", "Files", "files-org", AccountType.ORG, owner);
        versions.create(new PracticeVersionService.NewPractice(
                workspace.getId(),
                "files-definition",
                new ItemKind("scm.pull_request"),
                DefinitionTestFixtures.definition(),
                null,
                0,
                null));

        byte[] zip = Objects.requireNonNull(client.get()
                .uri("/workspaces/{workspace}/practices/files-definition/files", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentType("application/zip")
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody());
        var folder = unzip(zip, "files-definition/");
        assertThat(folder).containsKey(PracticeFiles.PRACTICE);

        var check = Objects.requireNonNull(client.post()
                .uri("/workspaces/{workspace}/practices/files-definition/file-check", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new PracticeFileCheckRequestDTO(folder))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(PracticeFileCheckDTO.class)
                .returnResult()
                .getResponseBody());
        assertThat(check.valid()).isTrue();
        assertThat(check.name()).isEqualTo(DefinitionTestFixtures.definition().name());
        assertThat(check.problems()).isEmpty();
    }

    @Test
    @WithAdminUser
    void shouldReportAProblemForAFolderThatCannotBecomeAPracticeAndSaveNothing() {
        var owner = persistUser("admin");
        var workspace = createWorkspace("files-check", "Files", "files-check-org", AccountType.ORG, owner);

        var check = Objects.requireNonNull(client.post()
                .uri("/workspaces/{workspace}/practices/new-practice/file-check", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new PracticeFileCheckRequestDTO(Map.of(PracticeFiles.PRACTICE, "Criteria only.\n")))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(PracticeFileCheckDTO.class)
                .returnResult()
                .getResponseBody());

        assertThat(check.valid()).isFalse();
        assertThat(check.problems())
                .extracting(PracticeFileProblemDTO::code)
                .containsExactly(PracticeFileProblemDTO.FRONT_MATTER_MISSING);
    }

    @Test
    @WithAdminUser
    void shouldAnswerNotFoundForAnUnknownPractice() {
        var owner = persistUser("admin");
        var workspace = createWorkspace("files-missing", "Files", "files-missing-org", AccountType.ORG, owner);

        client.get()
                .uri("/workspaces/{workspace}/practices/no-such-practice/files", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    @WithMentorUser
    void shouldForbidAMemberWhoIsNotAnAdminToDownloadOrCheck() {
        var member = persistUser("mentor");
        var owner = persistUser("files-member-owner");
        var workspace = createWorkspace("files-member", "Member", "files-member-org", AccountType.ORG, owner);
        ensureMembership(workspace, member, Membership.WorkspaceRole.MEMBER);

        client.get()
                .uri("/workspaces/{workspace}/practices/any-practice/files", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        client.post()
                .uri("/workspaces/{workspace}/practices/any-practice/file-check", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new PracticeFileCheckRequestDTO(Map.of(PracticeFiles.PRACTICE, "Criteria only.\n")))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    @WithMentorUser
    void shouldAnswerNotFoundToSomeoneOutsideTheWorkspace() {
        persistUser("mentor");
        var owner = persistUser("files-owner");
        var workspace = createWorkspace("files-private", "Private", "files-private-org", AccountType.ORG, owner);

        client.get()
                .uri("/workspaces/{workspace}/practices/any-practice/files", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    private static Map<String, String> unzip(byte[] zip, String prefix) throws IOException {
        var files = new HashMap<String, String>();
        try (var input = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                assertThat(entry.getName()).startsWith(prefix);
                files.put(
                        entry.getName().substring(prefix.length()),
                        new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }
}
