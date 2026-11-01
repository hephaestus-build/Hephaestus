package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import de.tum.cit.aet.hephaestus.workspace.WorkspaceProperties;
import de.tum.cit.aet.hephaestus.workspace.dto.GitLabPreflightRequestDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

@Tag("unit")
class GitLabPreflightControllerTest {

    private final GitLabPreflightService service = mock(GitLabPreflightService.class);
    private final GitLabPreflightController controller = new GitLabPreflightController(
            service, new WorkspaceProperties(false, null, false, null, WorkspaceProperties.CreationPolicy.ADMIN_ONLY));

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldRefuseANonAdminBeforeAnyGitLabCallWhenOnlyAdminsCreateWorkspaces() {
        var request = new GitLabPreflightRequestDTO("glpat-not-real", null, null);

        assertForbidden(() -> controller.gitLabPreflight(request));
        assertForbidden(() -> controller.listGitLabGroups(request));
        assertForbidden(() -> controller.listGitLabProjects(request, 1, null));
        verifyNoInteractions(service);
    }

    private static void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        refused -> assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }
}
