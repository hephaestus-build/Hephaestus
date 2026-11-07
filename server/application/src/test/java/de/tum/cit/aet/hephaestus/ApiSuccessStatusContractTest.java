package de.tum.cit.aet.hephaestus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.tum.cit.aet.hephaestus.core.auth.export.AccountExport;
import de.tum.cit.aet.hephaestus.core.auth.export.AccountExportService;
import de.tum.cit.aet.hephaestus.core.auth.web.AccountExportController;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class ApiSuccessStatusContractTest {
    @Test
    void shouldReturnAcceptedWithTheCreatedExportWhenExportIsRequested() throws Exception {
        var exports = mock(AccountExportService.class);
        var export = new AccountExport(42L);
        export.setId(17L);
        when(exports.requestExport(42L)).thenReturn(export);
        var mvc = MockMvcBuilders.standaloneSetup(new AccountExportController(exports))
                .build();
        var jwt =
                Jwt.withTokenValue("test").header("alg", "ES256").subject("42").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        try {
            mvc.perform(post("/user/exports"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.id").value(17))
                    .andExpect(jsonPath("$.status").value("PENDING"));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @ParameterizedTest
    @CsvSource({
        "/admin/login-providers/{registrationId}, delete, 204, -",
        "/admin/practice-catalog/groups, post, 201, CatalogGroup",
        "/admin/practice-catalog/practices, post, 201, CatalogPractice",
        "/auth/logout, post, 204, -",
        "/user, delete, 204, -",
        "/user/exports, post, 202, ExportCreated",
        "/user/sessions, delete, 204, -",
        "/user/sessions/{jti}, delete, 204, -",
        "/workspaces/{workspaceSlug}/repositories, post, 201, -",
        "/workspaces/{workspaceSlug}/repositories, delete, 204, -"
    })
    void shouldPublishTheRuntimeStatusAndBodyWhenGeneratingTheApi(
            String path, String method, String code, String schema) throws Exception {
        Object yaml = new Yaml().load(Files.readString(Path.of("..", "openapi.yaml")));
        var responses = new JsonMapper()
                .valueToTree(yaml)
                .path("paths")
                .path(path)
                .path(method)
                .path("responses");
        assertThat(responses.has(code)).isTrue();
        assertThat(responses.has("200")).isFalse();
        var response = responses.path(code);
        if (schema.equals("-")) {
            assertThat(response.has("content")).isFalse();
        } else {
            assertThat(response.path("content")
                            .path("application/json")
                            .path("schema")
                            .path("$ref")
                            .asString())
                    .isEqualTo("#/components/schemas/" + schema);
        }
    }
}
