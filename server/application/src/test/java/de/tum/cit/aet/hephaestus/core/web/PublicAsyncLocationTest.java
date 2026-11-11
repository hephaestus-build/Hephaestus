package de.tum.cit.aet.hephaestus.core.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.AuthPropertiesFixture;
import de.tum.cit.aet.hephaestus.core.auth.export.AccountExport;
import de.tum.cit.aet.hephaestus.core.auth.export.AccountExportService;
import de.tum.cit.aet.hephaestus.core.auth.web.AccountExportController;
import de.tum.cit.aet.hephaestus.practices.scan.ScanController;
import de.tum.cit.aet.hephaestus.practices.scan.ScanExportService;
import de.tum.cit.aet.hephaestus.practices.scan.ScanQueryService;
import de.tum.cit.aet.hephaestus.practices.scan.ScanReportItemsService;
import de.tum.cit.aet.hephaestus.practices.scan.ScanRequestService;
import de.tum.cit.aet.hephaestus.practices.scan.dto.ScanSummaryDTO;
import de.tum.cit.aet.hephaestus.practices.scan.dto.StartScanRequestDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.net.URI;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class PublicAsyncLocationTest extends BaseUnitTest {
    @BeforeEach
    void authenticateOnWorkspaceHost() {
        var jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject("41")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        var request = new MockHttpServletRequest();
        request.setScheme("https");
        request.setServerName("workspace.example.test");
        request.addHeader("X-Forwarded-Host", "other.example.test");
        request.addHeader("X-Forwarded-Prefix", "/api");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clearRequest() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldPublishTheExportStatusOnTheConfiguredApiOrigin() {
        var service = mock(AccountExportService.class);
        var export = new AccountExport();
        export.setId(13L);
        export.setAccountId(41L);
        when(service.requestExport(41L)).thenReturn(export);
        var controller = new AccountExportController(service, urls());

        var response = controller.requestExport();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getLocation())
                .isEqualTo(URI.create("https://public.example.test/api/user/exports/13"));
        assertThat(response.getBody())
                .isNotNull()
                .satisfies(body -> assertThat(body.id()).isEqualTo(13L));
    }

    @Test
    void shouldPublishTheScanStatusOnTheConfiguredApiOrigin() {
        var requests = mock(ScanRequestService.class);
        var request = mock(StartScanRequestDTO.class);
        var summary = mock(ScanSummaryDTO.class);
        UUID scan = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID key = UUID.fromString("22222222-2222-4222-8222-222222222222");
        var workspace = new WorkspaceContext(7L, "workspace", "Workspace", null, null, Set.of());
        when(summary.id()).thenReturn(scan);
        when(requests.start(7L, 41L, key, request)).thenReturn(summary);
        var controller = new ScanController(
                mock(ScanQueryService.class),
                urls(),
                mock(ScanExportService.class),
                requests,
                mock(ScanReportItemsService.class));

        var response = controller.startScan(workspace, key, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getLocation())
                .isEqualTo(URI.create("https://public.example.test/api/workspaces/workspace/practices/scans/" + scan));
        assertThat(response.getBody()).isSameAs(summary);
    }

    private static PublicApiUrls urls() {
        return new PublicApiUrls(AuthPropertiesFixture.withIssuer(URI.create("https://public.example.test")));
    }
}
