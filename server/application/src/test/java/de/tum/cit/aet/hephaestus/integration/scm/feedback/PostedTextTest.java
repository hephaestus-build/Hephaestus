package de.tum.cit.aet.hephaestus.integration.scm.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryContent;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
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
            @Nullable String header,
            @Nullable String marker,
            @Nullable String posted,
            @Nullable Rendered github,
            @Nullable Rendered gitlab) {
        /** The bytes a writer sends: the posted text, then the hidden markers it appends. */
        String wire() {
            // The writers put a line end before their markers.
            if (marker != null && !marker.startsWith("\n"))
                throw new IllegalArgumentException("A marker starts a line.");
            return (header == null ? "" : header + "\n\n") + PostedText.of(body) + (marker == null ? "" : marker);
        }

        String raw() {
            return (header == null ? "" : header + "\n\n") + body + (marker == null ? "" : marker);
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

    private static final Pattern OWN_MARKER = Pattern.compile("<!--[ ]*hephaestus[^<>]*-->");

    // The bot's own header links the reviewed file.
    private static final Pattern OWN_LINK = Pattern.compile(
            "<a [^>]*href=\"https://github\\.com/HephaestusTest/practice-validation/blob/[^\"]*\"[^>]*>[^<]*</a>");

    private static final Pattern TAG = Pattern.compile("<(/?)([A-Za-z][A-Za-z0-9-]*)([^>]*)>");

    // Paragraphs, line breaks, code, emphasis and Unicode emoji.
    private static final Set<String> ALLOWED = Set.of("p", "br", "code", "em", "strong", "g-emoji", "gl-emoji");

    // GitLab marks an escaped character, and shows a colour chip beside a colour in code.
    private static final Pattern ALLOWED_SPAN = Pattern.compile(
            "\\s+data-escaped-char=\"\"(?:\\s+data-sourcepos=\"[^\"]*\")?|\\s+class=\"gfm-color_chip\"|\\s+style=\"background-color: [^\";]*;?\"");

    /**
     * A rendered comment holds only paragraphs, line breaks, code, emphasis, Unicode emoji and GitLab's escaped
     * characters and colour chips: no link, mention, reference, image, block, math, inline diff or hidden markup.
     */
    static void assertQuiet(String html) {
        String visible =
                OWN_LINK.matcher(OWN_MARKER.matcher(html).replaceAll("")).replaceAll("");
        assertThat(visible)
                .doesNotContainPattern("<[a-z][^>]*\\son[a-z]+=")
                .doesNotContain("<!--")
                .doesNotContain("<?")
                .doesNotContain("<![CDATA[")
                .doesNotContain("<!DOCTYPE");
        Matcher tag = TAG.matcher(visible);
        while (tag.find()) {
            String name = tag.group(2).toLowerCase(Locale.ROOT);
            boolean allowed = ALLOWED.contains(name)
                    || (name.equals("span")
                            && (!tag.group(1).isEmpty()
                                    || ALLOWED_SPAN.matcher(tag.group(3)).matches()));
            assertThat(allowed).as("%s in %s", tag.group(), visible).isTrue();
        }
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
        // GitLab deletes carriage returns before it reads the lines; nothing else is taken out first.
        assertThat(QUICK_ACTION.matcher(text.wire().replace("\r", "")).find()).isFalse();
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
                .isEqualTo("Ask `@alice` `<b>now</b>`\n\n<!-- hephaestus-feedback-copy:1 -->");
    }

    @Test
    void shouldWriteOneLineOfLiteralTextAndCodeThatNoBacktickEnds() {
        assertThat(PostedText.plain("Use *x*\nthen @alice, #1")).isEqualTo("Use \\*x\\* then `@alice`, `#1`");
        assertThat(PostedText.inlineCode("a`b <c>")).isEqualTo("``a`b <c>``");
    }

    @Test
    void shouldKeepCodeSpansEmphasisAndPunctuationAsWrittenWhenTheTextIsMarkdown() {
        assertThat(PostedText.of("This catch returns `false` for every exception without logging. A missing user and a"
                        + " failed save look identical; log the exception here, @alice.\nSee **`deactivateUser`**, such"
                        + " as `Closes #123` and _always_ www.example.com."))
                .isEqualTo("This catch returns `false` for every exception without logging. A missing user and a"
                        + " failed save look identical; log the exception here, `@alice`.\nSee"
                        + " **`deactivateUser`**, such as `Closes #123` and *always* `www.example.com`.");
    }

    @Test
    void shouldWriteACodeSpanThatItsOwnBackticksDoNotEndWhenTheCodeHoldsBackticks() {
        assertThat(PostedText.of("Use `` a`b `` and ``` `` ``` here")).isEqualTo("Use ``a`b`` and ``` `` ``` here");
    }

    @Test
    void shouldJoinCodeSpansWhenCodeStandsRightNextToANameOrAReference() {
        assertThat(PostedText.of("`x`@bob and #1`y`")).isEqualTo("`x@bob` and `#1y`");
    }

    @Test
    void shouldKeepEveryLineOutOfBlocksWhenALineStartsWithMarkdown() {
        assertThat(PostedText.of("# Title\n1. one\n- two\n> quote\n/title x\n---\n[a](https://x.example)"))
                .isEqualTo("\\# Title\n1\\. one\n\\- two\n&gt; quote\n\\/title x\n\\---\n`[a](https://x.example)`");
    }

    @Test
    void shouldPostACharacterReferenceAsWrittenWhenItWouldDecodeToAMentionOrABlock() {
        assertThat(PostedText.of("&#35;1@alice")).isEqualTo("`&#35;1@alice`");
        assertThat(PostedText.of("@alice&#35;3625")).isEqualTo("`@alice&#35;3625`");
        assertThat(PostedText.of("&#32;# Forged heading")).isEqualTo("`&#32;#` Forged heading");
        assertThat(PostedText.of("Hello\n&#32;===")).isEqualTo("Hello\n`&#32;===`");
        assertThat(PostedText.of("***&#8203;***")).isEqualTo("***`&#8203`;***");
        assertThat(PostedText.of("R&D &amp; QA")).isEqualTo("R&amp;D &amp;amp; QA");
    }

    @Test
    void shouldPostANameAsCodeWhenItFollowsAReference() {
        assertThat(PostedText.of("#1@alice")).isEqualTo("`#1@alice`");
    }

    @Test
    void shouldEscapeTheDelimitersWhenEmphasisHoldsNothingVisible() {
        assertThat(PostedText.of("a *\u001F* b")).isEqualTo("a \\*\u001F\\* b");
    }

    @Test
    void shouldPostAddressesCommitsAndLinkSyntaxAsCode() {
        assertThat(PostedText.of("See www.@alice, alice@example.com and 11d4b03dff9c29d6f211734ee063fbb8696ceb83."))
                .isEqualTo("See `www.@alice`, `alice@example.com` and `11d4b03dff9c29d6f211734ee063fbb8696ceb83`.");
        assertThat(PostedText.of("[text](relative), [some text](//evil.example/) and [x][1]"))
                .isEqualTo("`[text](relative)`, `[some text](//evil.example/)` and `[x][1]`");
        assertThat(PostedText.of("a deadbeef, 1234567 and decade")).isEqualTo("a `deadbeef`, `1234567` and decade");
    }

    @Test
    void shouldPostEveryCommitLikeRunAsCodeWhateverItsDigitsAndLetters() {
        assertThat(PostedText.of("Build 2036564 broke it; bdcfdcf and 20365645 too."))
                .isEqualTo("Build `2036564` broke it; `bdcfdcf` and `20365645` too.");
        String sha256 = "ab".repeat(32);
        assertThat(PostedText.of("Image " + sha256 + ".")).isEqualTo("Image `" + sha256 + "`.");
    }

    @Test
    void shouldPostAProjectPathEmojiDiffMarksAndMilestonesAsCode() {
        assertThat(PostedText.of("see group/project> first")).isEqualTo("see `group/project>` first");
        assertThat(PostedText.of(":shipit: and :octocat:")).isEqualTo("`:shipit:` and `:octocat:`");
        assertThat(PostedText.of("{+ added +} and {- removed -}")).isEqualTo("`{+` added `+}` and `{-` removed `-}`");
        assertThat(PostedText.of("%-rc1, %.x and 50%.")).isEqualTo("`%-rc1`, `%.x` and `50%`.");
    }

    @Test
    void shouldPostMathReferencesInAnyCaseAdvisoriesAndIterationsAsCode() {
        assertThat(PostedText.of("Run it once ($O(n)$ cost).")).isEqualTo("Run it once (`$O(n)$` cost).");
        assertThat(PostedText.of("$\\color{red}\\Huge\\text{Approved by maintainers}$"))
                .isEqualTo("`$\\color{red}\\Huge\\text{Approved` by `maintainers}$`");
        assertThat(PostedText.of("$`x`$ and $$x$$")).isEqualTo("`$x$` and `$$x$$`");
        assertThat(PostedText.of("gh-26 and gH-26")).isEqualTo("`gh-26` and `gH-26`");
        assertThat(PostedText.of("GHSA-jfh8-c2jp-5v3q and cve-2021-44228"))
                .isEqualTo("`GHSA-jfh8-c2jp-5v3q` and `cve-2021-44228`");
        assertThat(PostedText.of("*iteration:1&*x")).isEqualTo("*`iteration:1&`*x");
    }

    @Test
    void shouldKeepALineOutOfBlocksWhenTheTextStartsWithAByteOrderMark() {
        assertThat(PostedText.of("\uFEFF# Heading")).isEqualTo("\\# Heading");
        assertThat(PostedText.of("\uFEFF1. one")).isEqualTo("1\\. one");
    }

    @Test
    void shouldKeepTheBotsOwnFileLinkAsALinkOnGitHub() throws IOException {
        var withLink = cases().stream().filter(c -> c.header() != null).toList();
        assertThat(withLink)
                .isNotEmpty()
                .allSatisfy(c -> assertThat(Objects.requireNonNull(c.github()).posted())
                        .contains("<a href=\"https://github.com/HephaestusTest/practice-validation/blob/"));
    }

    @Test
    void shouldStayWithinTheCommentLimitOnceEscapedAndSayThatItWasShortened() {
        String plain = "a".repeat(59_000);
        assertThat(PostedText.of(plain)).isEqualTo(plain);

        String posted = PostedText.of("@a & ".repeat(15_000));
        assertThat(posted.length()).isLessThanOrEqualTo(PostedText.MAX_POSTED_TEXT);
        assertThat(posted).endsWith(PostedText.SHORTENED).doesNotEndWith("&" + PostedText.SHORTENED);
    }

    @Test
    void shouldStayWithinTheCommentLimitWhenTheCutEndsACodeSpanEarly() {
        // Cut before its closing backtick, the span becomes text whose names are code: longer than the span was.
        String posted = PostedText.of("`" + "@a ".repeat(5_000) + "`" + "x".repeat(60_000));
        assertThat(posted.length()).isLessThanOrEqualTo(PostedText.MAX_POSTED_TEXT);
        assertThat(posted).endsWith(PostedText.SHORTENED);
    }
}
