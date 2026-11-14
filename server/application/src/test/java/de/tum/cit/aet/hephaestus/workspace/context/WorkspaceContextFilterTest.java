package de.tum.cit.aet.hephaestus.workspace.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import de.tum.cit.aet.hephaestus.core.auth.AuthPropertiesFixture;
import de.tum.cit.aet.hephaestus.core.auth.spi.AdminPasskeyAccess;
import de.tum.cit.aet.hephaestus.core.auth.spi.WorkspaceElevationAudit;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.CurrentAccountUsers;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceSlugHistoryRepository;
import de.tum.cit.aet.hephaestus.workspace.validation.WorkspaceSlugValidator;
import jakarta.servlet.FilterChain;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** A registry route is a whole path segment: a workspace whose slug starts with one still gets its context. */
class WorkspaceContextFilterTest extends BaseUnitTest {

    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final FilterChain chain = mock(FilterChain.class);
    private final WorkspaceContextFilter filter = new WorkspaceContextFilter(
            workspaces,
            mock(MembershipRepository.class),
            mock(CurrentAccountUsers.class),
            mock(WorkspaceSlugHistoryRepository.class),
            JsonMapper.builder().build(),
            mock(WorkspaceElevationAudit.class),
            mock(WorkspaceActorSelector.class),
            mock(AdminPasskeyAccess.class),
            AuthPropertiesFixture.defaults());

    static Stream<String> slugsThatStartWithARegistrySegment() {
        return WorkspaceContextFilter.REGISTRY_SEGMENTS.stream()
                .flatMap(segment -> Stream.of(segment + "-team", segment + "1"));
    }

    /** A workspace slug that starts with a registry word is a workspace: here an unknown one, so the answer is 404. */
    @ParameterizedTest
    @MethodSource("slugsThatStartWithARegistrySegment")
    void shouldTreatASlugThatStartsWithARegistrySegmentAsAWorkspace(String slug) throws Exception {
        var response = new MockHttpServletResponse();

        filter.doFilter(get("/workspaces/" + slug + "/context-echo"), response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        verifyNoInteractions(chain);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/workspaces/providers", "/workspaces/gitlab/groups", "/workspaces/gitlab/preflight"})
    void shouldPassARegistryRouteWithoutAWorkspace(String path) throws Exception {
        var request = get(path);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(workspaces, never()).findByWorkspaceSlug(anyString());
    }

    @Test
    void shouldReserveEveryRegistrySegmentAsASlug() {
        assertThat(WorkspaceContextFilter.REGISTRY_SEGMENTS).noneMatch(WorkspaceSlugValidator::isAssignable);
    }

    private static MockHttpServletRequest get(String path) {
        var request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        return request;
    }
}
