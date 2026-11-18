package de.tum.cit.aet.hephaestus.core.webapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

/**
 * The operator's legal pages as the chart mounts them: a ConfigMap volume, where {@code imprint.md} is a symbolic link
 * to {@code ..data/imprint.md} and {@code ..data} links to the current hidden version. Spring's resource handler serves
 * a file below its location only, so this proves that the links are followed rather than refused.
 */
class WebAppLegalOverridesTest extends BaseUnitTest {

    @Test
    void shouldServeAnOverrideThatTheConfigMapMountsAsASymbolicLink(@TempDir Path directory) throws Exception {
        Path version = Files.createDirectory(directory.resolve("..2026_11_18_00_00_00.000000001"));
        Files.writeString(version.resolve("imprint.md"), "# Imprint of this deployment\n");
        Files.createSymbolicLink(directory.resolve("..data"), version.getFileName());
        Files.createSymbolicLink(directory.resolve("imprint.md"), Path.of("..data", "imprint.md"));

        assertEquals(
                "# Imprint of this deployment\n", read(directory, "imprint.md").getContentAsString());
        // A file the operator did not supply is not found, so the web app shows its placeholder.
        assertThrows(NoResourceFoundException.class, () -> read(directory, "privacy.md"));
    }

    private static MockHttpServletResponse read(Path directory, String file) throws Exception {
        var handler = new ResourceHttpRequestHandler();
        handler.setLocations(List.of(new DefaultResourceLoader()
                .getResource(WebAppConfiguration.legalOverridesLocation(directory.toString()))));
        handler.setServletContext(new MockServletContext());
        handler.afterPropertiesSet();
        var request = new MockHttpServletRequest("GET", "/legal-overrides/" + file);
        request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, file);
        var response = new MockHttpServletResponse();
        response.setCharacterEncoding(StandardCharsets.UTF_8);
        handler.handleRequest(request, response);
        return response;
    }
}
