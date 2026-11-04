package de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackDeliveryException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmVersion;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.web.reactive.function.client.WebClient;

class GitLabDiffVersionReaderTest extends BaseUnitTest {

    private static final String BASE = "a".repeat(40);
    private static final String START = "b".repeat(40);
    private static final String HEAD = "c".repeat(40);
    private static final String OTHER = "d".repeat(40);
    private static final String VERSIONS = "/api/v4/projects/group%2Fproject/merge_requests/42/versions";
    private static final GitLabMrResolver.MrCoordinates MR = new GitLabMrResolver.MrCoordinates("group/project", 42);
    private static final ItemVersion PINNED = ScmVersion.pullRequest(BASE, HEAD, "title", null);

    @Mock
    private GitLabTokenService tokenService;

    private MockWebServer gitlab;
    private GitLabDiffVersionReader reader;

    @BeforeEach
    void setUp() throws IOException {
        gitlab = new MockWebServer();
        gitlab.start();
        when(tokenService.resolveServerUrl(1L)).thenReturn(gitlab.url("/").toString());
        when(tokenService.getAccessToken(1L)).thenReturn("glpat-secret");
        reader = new GitLabDiffVersionReader(tokenService, () -> WebClient.builder());
    }

    @AfterEach
    void tearDown() throws IOException {
        gitlab.close();
    }

    @Test
    void shouldReadThePinnedVersionWhenItIsOnALaterPage() throws InterruptedException {
        answer(page(100, OTHER));
        answer("[" + entry(7, HEAD) + "]");
        answer(detail(
                7, START, "[{\"old_path\":\"a.java\",\"new_path\":\"a.java\",\"diff\":\"@@ -1 +1 @@\\n-x\\n+y\\n\"}]"));

        var version = Objects.requireNonNull(reader.read(1L, MR, PINNED));

        assertThat(version.id()).isEqualTo(7);
        assertThat(version.diffs())
                .singleElement()
                .satisfies(diff -> assertThat(diff.newPath()).isEqualTo("a.java"));
        var first = gitlab.takeRequest();
        assertThat(first.getTarget()).isEqualTo(VERSIONS + "?per_page=100&page=1");
        assertThat(first.getHeaders().get("Authorization")).isEqualTo("Bearer glpat-secret");
        assertThat(gitlab.takeRequest().getTarget()).isEqualTo(VERSIONS + "?per_page=100&page=2");
        assertThat(gitlab.takeRequest().getTarget()).isEqualTo(VERSIONS + "/7");
    }

    // A missing reviewed version says nothing about the cited line, so it cannot justify a plain comment.
    @Test
    void shouldFailWhenACompleteReadLacksThePinnedPair() {
        answer("[" + entry(3, OTHER) + "]");
        assertThatThrownBy(() -> reader.read(1L, MR, PINNED)).isInstanceOf(FeedbackDeliveryException.class);

        answer("[]");
        assertThatThrownBy(() -> reader.read(1L, MR, PINNED)).isInstanceOf(FeedbackDeliveryException.class);

        answer(page(100, OTHER));
        answer("[]");
        assertThatThrownBy(() -> reader.read(1L, MR, PINNED)).isInstanceOf(FeedbackDeliveryException.class);
        assertThat(gitlab.getRequestCount()).isEqualTo(4);
    }

    @Test
    void shouldFailWhenTheProviderAnswersWithAnError() {
        gitlab.enqueue(new MockResponse.Builder().code(503).build());

        assertThatThrownBy(() -> reader.read(1L, MR, PINNED)).isInstanceOf(FeedbackDeliveryException.class);
    }

    @Test
    void shouldFailWhenTheAnswerIsMalformedOrEmpty() {
        answer("{\"message\":\"not a list\"}");
        assertThatThrownBy(() -> reader.read(1L, MR, PINNED)).isInstanceOf(FeedbackDeliveryException.class);

        gitlab.enqueue(new MockResponse.Builder().code(200).build());
        assertThatThrownBy(() -> reader.read(1L, MR, PINNED)).isInstanceOf(FeedbackDeliveryException.class);
    }

    @Test
    void shouldFailWhenTheVersionDetailIsIncompleteOrDisagreesWithItsEntry() {
        answer("[" + entry(7, HEAD) + "]");
        answer(detail(7, OTHER, "[]"));
        assertThatThrownBy(() -> reader.read(1L, MR, PINNED)).isInstanceOf(FeedbackDeliveryException.class);

        answer("[" + entry(7, HEAD) + "]");
        answer(detail(7, START, null));
        assertThatThrownBy(() -> reader.read(1L, MR, PINNED)).isInstanceOf(FeedbackDeliveryException.class);
    }

    @Test
    void shouldFailWhenEveryPageIsFullUpToThePageBudget() {
        for (int page = 0; page < 50; page++) answer(page(100, OTHER));

        assertThatThrownBy(() -> reader.read(1L, MR, PINNED)).isInstanceOf(FeedbackDeliveryException.class);
        assertThat(gitlab.getRequestCount()).isEqualTo(50);
    }

    private void answer(String json) {
        gitlab.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(json)
                .build());
    }

    private static String page(int size, String head) {
        return IntStream.rangeClosed(1, size)
                .mapToObj(id -> entry(id, head))
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String entry(int id, String head) {
        return "{\"id\":" + id + ",\"head_commit_sha\":\"" + head + "\",\"base_commit_sha\":\"" + BASE
                + "\",\"start_commit_sha\":\"" + START + "\"}";
    }

    private static String detail(int id, String start, @Nullable String diffs) {
        return "{\"id\":" + id + ",\"head_commit_sha\":\"" + HEAD + "\",\"base_commit_sha\":\"" + BASE
                + "\",\"start_commit_sha\":\"" + start + "\"" + (diffs == null ? "" : ",\"diffs\":" + diffs) + "}";
    }
}
