package de.tum.cit.aet.hephaestus.productfeedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class IssueRendererTest {

    private static Draft bug(String observed) {
        return new Draft(
                Draft.Disposition.ISSUE,
                Draft.Type.BUG,
                "Title @octocat #12",
                observed,
                "",
                List.of("Open [x](y)"),
                "",
                "",
                List.of(),
                "",
                7L,
                false,
                false,
                true,
                Draft.Confidence.HIGH);
    }

    @Test
    void shouldRenderPersonTextAsInertTextWhenItCarriesMarkup() {
        var rendered = IssueRenderer.render(
                bug("See <img src=x> by @octocat in octo-org/repo#3 and #4, also https://evil.example/x"),
                new IssueRenderer.Context("1.0.0", "/w/$workspaceSlug/activity", null),
                "example-org/product",
                null,
                null,
                null,
                "marker-1",
                "1.0.0");

        assertThat(rendered.body())
                .doesNotContain("<img")
                .contains("`@octocat`")
                .contains("`octo-org/repo#3`")
                .contains("`#4`")
                .contains("`https://evil.example/x`")
                .contains("Open \\[x\\]\\(y\\)")
                .contains("Possibly related: https://github.com/example-org/product/issues/7")
                .contains("<!-- hephaestus-product-feedback:marker-1 -->")
                .doesNotContain("Private because")
                .doesNotContain("instance admin console");
        assertThat(rendered.title()).isEqualTo("Title octocat issue 12");
    }

    @Test
    void shouldStateTheReasonAndTheReportOnlyOnAPrivateIssue() {
        var rendered = IssueRenderer.render(
                bug("It breaks."),
                new IssueRenderer.Context(null, null, null),
                "example-org/private",
                "security",
                "4d1c0e5e-report",
                null,
                "marker-2",
                null);

        assertThat(rendered.body())
                .startsWith("**Private because:** security")
                .contains("Report `4d1c0e5e-report` in the instance admin console.");
    }

    @Test
    void shouldReadADraftFromAFencedAnswerAndDropARelatedIssueOutsideTheSnapshot() throws Exception {
        String answer = """
                Here is the issue:
                ```json
                {"disposition":"issue","type":"feature","title":"Filter by repository","problem":"Too much at once",
                 "criteria":["One repository at a time"],"related":999,"coherent":true,"confidence":"high"}
                ```
                """;

        Draft draft = Draft.parse(answer, JsonMapper.builder().build(), Set.of(12L));

        assertThat(draft.disposition()).isEqualTo(Draft.Disposition.ISSUE);
        assertThat(draft.type()).isEqualTo(Draft.Type.FEATURE);
        assertThat(draft.criteria()).containsExactly("One repository at a time");
        assertThat(draft.related()).isNull();
        assertThat(draft.confidence()).isEqualTo(Draft.Confidence.HIGH);
    }

    @Test
    void shouldRefuseAnAnswerWithoutAKnownDisposition() {
        assertThatThrownBy(() -> Draft.parse(
                        "{\"disposition\":\"MAYBE\",\"confidence\":\"HIGH\"}",
                        JsonMapper.builder().build(),
                        Set.of()))
                .isInstanceOf(Draft.Invalid.class);
    }
}
