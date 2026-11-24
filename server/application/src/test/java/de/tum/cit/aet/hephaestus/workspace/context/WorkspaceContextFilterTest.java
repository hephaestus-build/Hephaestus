package de.tum.cit.aet.hephaestus.workspace.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.AdminPasskeyAccess;
import de.tum.cit.aet.hephaestus.core.auth.spi.WorkspaceElevationAudit;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.core.security.UserViewContextHolder;
import de.tum.cit.aet.hephaestus.core.web.ApiPath;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.CurrentAccountUsers;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceKind;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceSlugHistoryRepository;
import de.tum.cit.aet.hephaestus.workspace.validation.WorkspaceSlugValidator;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * A registry route is a whole path segment: a workspace whose slug starts with one still gets its context. A personal
 * space admits only its owner.
 */
class WorkspaceContextFilterTest extends BaseUnitTest {

    private static final String PERSONAL_SLUG = "me--abcdefghijklmnop";

    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final MembershipRepository memberships = mock(MembershipRepository.class);
    private final WorkspaceElevationAudit elevationAudit = mock(WorkspaceElevationAudit.class);
    private final FilterChain chain = mock(FilterChain.class);
    private final WorkspaceContextFilter filter = new WorkspaceContextFilter(
            workspaces,
            memberships,
            mock(CurrentAccountUsers.class),
            mock(WorkspaceSlugHistoryRepository.class),
            JsonMapper.builder().build(),
            elevationAudit,
            mock(WorkspaceActorSelector.class),
            mock(AdminPasskeyAccess.class));

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
        verify(workspaces, never()).findAnyKindByWorkspaceSlug(anyString());
    }

    @Test
    void shouldReserveEveryRegistrySegmentAsASlug() {
        assertThat(WorkspaceContextFilter.REGISTRY_SEGMENTS).noneMatch(WorkspaceSlugValidator::isAssignable);
    }

    /** An instance admin is refused another person's personal space before elevation, so no elevation is recorded. */
    @Test
    void shouldRefuseAnInstanceAdminAPersonalSpaceWithoutElevation() throws Exception {
        personalSpace(7L);
        signIn(8L);
        var response = new MockHttpServletResponse();

        filter.doFilter(get("/workspaces/" + PERSONAL_SLUG + "/personal-test/echo"), response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        verifyNoInteractions(chain, elevationAudit, memberships);
    }

    /** A user view acts as the viewed account, so even the owner viewing as themselves does not open the space. */
    @Test
    void shouldRefuseAUserViewOfAPersonalSpace() throws Exception {
        personalSpace(7L);
        signIn(7L);
        UserViewContextHolder.set(new UserViewContextHolder.View(1L, 7L));
        var response = new MockHttpServletResponse();

        filter.doFilter(get("/workspaces/" + PERSONAL_SLUG + "/personal-test/echo"), response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        verifyNoInteractions(chain, elevationAudit, memberships);
    }

    /** An owner who is an instance admin and holds no active membership is never elevated into the space. */
    @Test
    void shouldNotElevateAnOwnerWithoutAMembership() throws Exception {
        personalSpace(7L);
        signIn(7L);
        var response = new MockHttpServletResponse();

        filter.doFilter(get("/workspaces/" + PERSONAL_SLUG + "/personal-test/echo"), response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        verifyNoInteractions(chain, elevationAudit);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
        UserViewContextHolder.clear();
    }

    private void personalSpace(long ownerId) {
        var space = Workspace.create(PERSONAL_SLUG, "Personal space", PERSONAL_SLUG, AccountType.USER);
        ReflectionTestUtils.setField(space, "id", 1L);
        ReflectionTestUtils.setField(space, "kind", WorkspaceKind.PERSONAL);
        ReflectionTestUtils.setField(space, "personalAccountId", ownerId);
        when(workspaces.findAnyKindByWorkspaceSlug(PERSONAL_SLUG)).thenReturn(Optional.of(space));
    }

    /** An instance admin, signed in as {@code accountId}. */
    private static void signIn(long accountId) {
        var jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(Long.toString(accountId))
                .claim("roles", List.of(SecurityUtils.APP_ADMIN_AUTHORITY))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    /** A request to {@code path}, given relative to the API. */
    private static MockHttpServletRequest get(String path) {
        var request = new MockHttpServletRequest("GET", ApiPath.of(path));
        request.setRequestURI(ApiPath.of(path));
        return request;
    }
}
