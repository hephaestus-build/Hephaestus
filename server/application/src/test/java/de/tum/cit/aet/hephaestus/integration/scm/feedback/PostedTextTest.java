package de.tum.cit.aet.hephaestus.integration.scm.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryContent;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Each case holds the text Hephaestus posts and the HTML that GitHub {@code POST /markdown} and GitLab
 * {@code POST /api/v4/markdown} rendered for it and for the raw text. {@code PostedTextLiveTest} records them.
 */
class PostedTextTest extends BaseUnitTest {

    record Rendered(String raw, String posted) {}

    record Case(
            String body,
            @Nullable String marker,
            @Nullable String posted,
            @Nullable Rendered github,
            @Nullable Rendered gitlab) {
        /** The bytes a writer sends: the posted text, then the hidden markers it appends. */
        String wire() {
            // The writers put a line end before their markers.
            if (marker != null && !marker.startsWith("\n"))
                throw new IllegalArgumentException("A marker starts a line.");
            return PostedText.of(body) + (marker == null ? "" : marker);
        }

        String raw() {
            return body + (marker == null ? "" : marker);
        }
    }

    static final String FIXTURE = "scm/mentions/posted-text.json";

    static List<Case> cases() throws IOException {
        try (InputStream json =
                Objects.requireNonNull(PostedTextTest.class.getClassLoader().getResourceAsStream(FIXTURE))) {
            return List.of(JsonMapper.builder().build().readValue(json, Case[].class));
        }
    }

    // GitLab's quick action extractor, as GitLab reads a note: a slash command at a line start, outside code.
    private static final Pattern QUICK_ACTION = Pattern.compile(
            "^\\/(?<cmd>[a-z0-9_]+)(?:[ ](?<arg>[^\\n]*))?(?:\\s*\\n|$)", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private static final Pattern FENCED = Pattern.compile("(?m)^```[\\s\\S]*?^```");

    private static final Pattern OWN_MARKER = Pattern.compile("<!--[ ]*hephaestus[^<>]*-->");

    /** What a rendered comment must not hold: a mention, an image, a script, an event attribute or hidden markup. */
    static void assertQuiet(String html) {
        String visible = OWN_MARKER.matcher(html).replaceAll("");
        assertThat(visible)
                .doesNotContain("user-mention")
                .doesNotContain("team-mention")
                .doesNotContain("data-reference-type=\"user\"")
                .doesNotContain("<img")
                .doesNotContain("<script")
                .doesNotContainPattern("<[a-z][^>]*\\son[a-z]+=")
                .doesNotContain("<!--")
                .doesNotContain("<?")
                .doesNotContain("<![CDATA[")
                .doesNotContain("<!DOCTYPE")
                .doesNotContainPattern("(?i)href=\"\\s*(?:javascript|data|vbscript):");
    }

    static boolean live(String html) {
        return html.contains("user-mention")
                || html.contains("team-mention")
                || html.contains("data-reference-type=\"user\"");
    }

    @ParameterizedTest
    @MethodSource("cases")
    void shouldPostTheTextTheProvidersRenderedQuietly(Case text) {
        assertThat(text.wire()).isEqualTo(text.posted());
        assertQuiet(Objects.requireNonNull(text.github()).posted());
        assertQuiet(Objects.requireNonNull(text.gitlab()).posted());
    }

    @ParameterizedTest
    @MethodSource("cases")
    void shouldLeaveNoLineThatGitLabRunsAsAQuickAction(Case text) {
        assertThat(QUICK_ACTION
                        .matcher(FENCED.matcher(text.wire()).replaceAll(""))
                        .find())
                .isFalse();
    }

    @Test
    void shouldHoldRawTextThatEachProviderRendersLive() throws IOException {
        for (Function<Case, @Nullable Rendered> provider :
                List.<Function<Case, @Nullable Rendered>>of(Case::github, Case::gitlab)) {
            var raw = cases().stream()
                    .map(c -> Objects.requireNonNull(provider.apply(c)).raw())
                    .toList();
            assertThat(raw.stream().filter(PostedTextTest::live).count()).isGreaterThanOrEqualTo(10);
            assertThat(raw.stream().filter(html -> html.contains("<img")).count())
                    .isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void shouldKeepTheHiddenMarkerAfterTheSafeText() {
        var content = new SummaryContent(
                UUID.randomUUID(),
                "Ask @alice <b>now</b>\n\n<!-- hephaestus-feedback-copy:1 -->",
                "key",
                "<!-- hephaestus-feedback-copy:1 -->");
        assertThat(PostedText.summary(content))
                .isEqualTo("Ask <code>@alice</code> &lt;b>now&lt;/b>\n\n<!-- hephaestus-feedback-copy:1 -->");
    }
}
