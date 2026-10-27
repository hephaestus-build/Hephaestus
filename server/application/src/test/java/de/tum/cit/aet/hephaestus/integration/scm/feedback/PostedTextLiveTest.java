package de.tum.cit.aet.hephaestus.integration.scm.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders each fixture case, raw and as posted, with GitHub {@code POST /markdown} and GitLab
 * {@code POST /api/v4/markdown}, and checks what {@link PostedTextTest} checks on the frozen copy. With
 * {@code POSTED_TEXT_RECORD} set it writes the renders back into the fixture.
 *
 * <p>Needs {@code GITHUB_TOKEN}, {@code GITLAB_URL} and {@code GITLAB_TOKEN}. {@code POSTED_TEXT_GITHUB_REPOSITORY}
 * and {@code POSTED_TEXT_GITLAB_PROJECT} name the repository whose members a name resolves to.
 */
@Tag("live")
class PostedTextLiveTest {

    @Test
    void shouldRenderEveryPostedTextQuietlyWhereItsRawTextIsLive() throws Exception {
        String github = System.getenv("GITHUB_TOKEN");
        String gitlabUrl = System.getenv("GITLAB_URL");
        String gitlab = System.getenv("GITLAB_TOKEN");
        assumeThat(github).as("GITHUB_TOKEN").isNotBlank();
        assumeThat(gitlabUrl).as("GITLAB_URL").isNotBlank();
        assumeThat(gitlab).as("GITLAB_TOKEN").isNotBlank();
        String repository = Objects.requireNonNullElse(
                System.getenv("POSTED_TEXT_GITHUB_REPOSITORY"), "HephaestusTest/practice-validation");
        String project =
                Objects.requireNonNullElse(System.getenv("POSTED_TEXT_GITLAB_PROJECT"), "hephaestus-test/sandbox");
        RestClient githubClient =
                RestClient.builder().baseUrl("https://api.github.com").build();
        RestClient gitlabClient = RestClient.builder().baseUrl(gitlabUrl).build();

        var recorded = new ArrayList<PostedTextTest.Case>();
        for (var text : PostedTextTest.cases()) {
            String wire = text.wire();
            var onGitHub = new PostedTextTest.Rendered(
                    gitHub(githubClient, github, repository, text.raw()),
                    gitHub(githubClient, github, repository, wire));
            var onGitLab = new PostedTextTest.Rendered(
                    gitLab(gitlabClient, gitlab, project, text.raw()), gitLab(gitlabClient, gitlab, project, wire));
            PostedTextTest.assertQuiet(onGitHub.posted());
            PostedTextTest.assertQuiet(onGitLab.posted());
            recorded.add(new PostedTextTest.Case(text.body(), text.header(), text.marker(), wire, onGitHub, onGitLab));
        }
        assertThat(recorded.stream()
                        .filter(c -> PostedTextTest.live(
                                Objects.requireNonNull(c.github()).raw())))
                .hasSizeGreaterThanOrEqualTo(10);
        assertThat(recorded.stream()
                        .filter(c -> PostedTextTest.live(
                                Objects.requireNonNull(c.gitlab()).raw())))
                .hasSizeGreaterThanOrEqualTo(10);
        if (System.getenv("POSTED_TEXT_RECORD") != null) {
            Files.writeString(
                    Path.of("src/test/resources", PostedTextTest.FIXTURE),
                    JsonMapper.builder()
                                    .enable(SerializationFeature.INDENT_OUTPUT)
                                    .build()
                                    .writeValueAsString(recorded)
                            + "\n");
        }
    }

    private static String gitHub(RestClient client, String token, String repository, String text) {
        // A text that renders to nothing comes back without a body.
        return Objects.requireNonNullElse(
                client.post()
                        .uri("/markdown")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("text", text, "mode", "gfm", "context", repository))
                        .retrieve()
                        .body(String.class),
                "");
    }

    private static String gitLab(RestClient client, String token, String project, String text) {
        Map<?, ?> rendered = Objects.requireNonNull(client.post()
                .uri("/api/v4/markdown")
                .header("PRIVATE-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("text", text, "gfm", true, "project", project))
                .retrieve()
                .body(Map.class));
        return String.valueOf(rendered.get("html"));
    }
}
