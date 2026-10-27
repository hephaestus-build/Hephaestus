package de.tum.cit.aet.hephaestus.integration.scm.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Renders each escaped fixture body with the providers' own Markdown renderers and finds no mention link in it.
 *
 * <p>Skipped unless {@code GITHUB_TOKEN} is set (GitHub) or {@code GITLAB_URL} and {@code GITLAB_TOKEN} are set
 * (GitLab). {@code MENTIONS_GITHUB_REPOSITORY} and {@code MENTIONS_GITLAB_PROJECT} name the repository whose members
 * the renderer resolves; both default to a test repository.
 */
@Tag("live")
class ProviderMentionsLiveTest {

    @ParameterizedTest
    @MethodSource("de.tum.cit.aet.hephaestus.integration.scm.feedback.ProviderMentionsTest#cases")
    void shouldRenderNoGitHubMention(ProviderMentionsTest.Case mention) {
        String token = System.getenv("GITHUB_TOKEN");
        assumeThat(token).as("GITHUB_TOKEN").isNotBlank();
        String html = RestClient.builder()
                .baseUrl("https://api.github.com")
                .build()
                .post()
                .uri("/markdown")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "text",
                        mention.escaped(),
                        "mode",
                        "gfm",
                        "context",
                        orDefault(System.getenv("MENTIONS_GITHUB_REPOSITORY"), "HephaestusTest/sandbox")))
                .retrieve()
                .body(String.class);
        assertThat(html).doesNotContain("user-mention").doesNotContain("team-mention");
    }

    @ParameterizedTest
    @MethodSource("de.tum.cit.aet.hephaestus.integration.scm.feedback.ProviderMentionsTest#cases")
    void shouldRenderNoGitLabMention(ProviderMentionsTest.Case mention) {
        String url = System.getenv("GITLAB_URL");
        String token = System.getenv("GITLAB_TOKEN");
        assumeThat(url).as("GITLAB_URL").isNotBlank();
        assumeThat(token).as("GITLAB_TOKEN").isNotBlank();
        Map<?, ?> rendered = RestClient.builder()
                .baseUrl(url)
                .build()
                .post()
                .uri("/api/v4/markdown")
                .header("PRIVATE-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "text",
                        mention.escaped(),
                        "gfm",
                        true,
                        "project",
                        orDefault(System.getenv("MENTIONS_GITLAB_PROJECT"), "hephaestus-test/sandbox")))
                .retrieve()
                .body(Map.class);
        assertThat(rendered).isNotNull();
        assertThat(String.valueOf(rendered.get("html")))
                .doesNotContain("data-reference-type=\"user\"")
                .doesNotContain("gfm-project_member");
    }

    private static String orDefault(@Nullable String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
