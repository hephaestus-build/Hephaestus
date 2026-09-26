package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.feature.FeatureFlag;
import de.tum.cit.aet.hephaestus.feature.FeatureFlagService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.workspace.dto.CreateWorkspaceRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.WorkspaceDTO;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class WorkspaceRegistryControllerTest {

    @Mock
    private WorkspaceService workspaceService;

    @Mock
    private WorkspaceQueryService workspaceQueryService;

    @Mock
    private WorkspaceProvisioningService workspaceProvisioningService;

    @Mock
    private FeatureFlagService featureFlagService;

    private WorkspaceRegistryController controller;

    @BeforeEach
    void setUp() {
        controller = controller(WorkspaceProperties.CreationPolicy.ADMIN_ONLY);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldRejectNonAdminWhenCreationPolicyIsAdminOnly() {
        authenticate(List.of());

        assertThatThrownBy(() -> controller.createWorkspace(request(IntegrationKind.GITHUB)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(workspaceService, workspaceProvisioningService);
    }

    @Test
    void shouldCreateWorkspaceWhenAdminUsesAdminOnlyPolicy() {
        authenticate(List.of(SecurityUtils.APP_ADMIN_AUTHORITY));
        var request = request(IntegrationKind.GITHUB);
        var workspace = new Workspace();
        workspace.setWorkspaceSlug(Objects.requireNonNull(request.workspaceSlug()));
        var dto = org.mockito.Mockito.mock(WorkspaceDTO.class);
        when(workspaceService.createWorkspaceWithInitialization(request)).thenReturn(workspace);
        when(workspaceQueryService.toWorkspaceDTO(workspace)).thenReturn(dto);
        var servletRequest = new MockHttpServletRequest("POST", "/workspaces");
        servletRequest.setScheme("http");
        servletRequest.setServerName("localhost");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));

        var response = controller.createWorkspace(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isSameAs(dto);
        assertThat(response.getHeaders().getLocation()).hasPath("/workspaces/test-workspace");
        verify(workspaceProvisioningService, never()).requireGitLabInstance(any());
    }

    @Test
    void shouldRejectGitLabWorkspaceWhenFeatureIsDisabled() {
        controller = controller(WorkspaceProperties.CreationPolicy.SELF_SERVICE);
        authenticate(List.of());
        when(featureFlagService.isEnabled(FeatureFlag.GITLAB_WORKSPACE_CREATION))
                .thenReturn(false);

        assertThatThrownBy(() -> controller.createWorkspace(request(IntegrationKind.GITLAB)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(workspaceService, workspaceProvisioningService);
    }

    @Test
    void shouldNotCreateGitLabWorkspaceWhenInstanceIsNotConfigured() {
        controller = controller(WorkspaceProperties.CreationPolicy.SELF_SERVICE);
        authenticate(List.of());
        when(featureFlagService.isEnabled(FeatureFlag.GITLAB_WORKSPACE_CREATION))
                .thenReturn(true);
        when(workspaceProvisioningService.requireGitLabInstance(null))
                .thenThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT));

        assertThatThrownBy(() -> controller.createWorkspace(request(IntegrationKind.GITLAB)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));
        verifyNoInteractions(workspaceService);
    }

    private WorkspaceRegistryController controller(WorkspaceProperties.CreationPolicy policy) {
        return new WorkspaceRegistryController(
                workspaceService,
                workspaceQueryService,
                workspaceProvisioningService,
                featureFlagService,
                new WorkspaceProperties(false, null, false, null, policy));
    }

    private static CreateWorkspaceRequestDTO request(IntegrationKind kind) {
        return new CreateWorkspaceRequestDTO(
                "test-workspace", "Test Workspace", "test-org", AccountType.ORG, 1L, kind, "test-token", null);
    }

    private static void authenticate(List<String> roles) {
        Instant issuedAt = Instant.now();
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("1")
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(60))
                .claim("roles", roles)
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
