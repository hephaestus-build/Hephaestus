package de.tum.cit.aet.hephaestus.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Guards the decision to ENFORCE {@code script-src 'self'} (see {@link SecurityHeaders}): it is safe
 * only because the swagger-ui bundle we ship serves every script externally from {@code 'self'} with no
 * inline {@code <script>} block. If a future swagger-ui webjar adds an inline bootstrap script, enforced
 * CSP would silently break the docs UI in production — this test fails first, at build time, with no
 * browser or network needed. It reads the ACTUAL shipped {@code index.html} from the classpath, so it
 * tracks whatever version is on the dependency tree.
 */
class SwaggerUiCspCompatibilityTest extends BaseUnitTest {

    private static final Pattern SCRIPT_TAG = Pattern.compile("(?i)<script\\b[^>]*>");

    @Test
    void shippedSwaggerUiHasNoInlineScriptSoEnforcedScriptSrcSelfIsSafe() throws Exception {
        Resource[] indexes = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/META-INF/resources/webjars/swagger-ui/*/index.html");

        assertThat(indexes)
                .as("swagger-ui webjar index.html must be on the classpath (springdoc serves it)")
                .isNotEmpty();

        for (Resource index : indexes) {
            String html = index.getContentAsString(StandardCharsets.UTF_8);
            Matcher tag = SCRIPT_TAG.matcher(html);
            while (tag.find()) {
                assertThat(tag.group())
                        .as(
                                "every swagger-ui <script> must be external (src=) for enforced script-src 'self'; %s",
                                index)
                        .contains("src=");
            }
            // No inline event handlers either (onload=/onclick= would also need 'unsafe-inline').
            assertThat(html.toLowerCase(Locale.ROOT))
                    .as("swagger-ui index.html must carry no inline event handlers; %s", index)
                    .doesNotContain("onload=")
                    .doesNotContain("onclick=");
        }
    }
}
