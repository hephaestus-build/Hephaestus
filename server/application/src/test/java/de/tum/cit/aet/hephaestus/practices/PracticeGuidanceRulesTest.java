package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PracticeGuidanceRulesTest extends BaseUnitTest {

    private static final String SQUARE =
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\"><rect class=\"pv-fill-ink\" "
                    + "x=\"1\" y=\"1\" width=\"8\" height=\"8\" rx=\"2\"/><text x=\"1\" y=\"9\">Hi</text></svg>";

    private static String svg(String body) {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\">" + body + "</svg>";
    }

    @Nested
    class Visual {

        @Test
        void shouldAcceptTheVisualWhenItDrawsShapesAndTextWithThemeClasses() {
            assertThatCode(() -> PracticeGuidanceRules.validate(new PracticeVisual(SQUARE, "A square")))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest
        @ValueSource(strings = {"""
                    <svg width="640" height="320" viewBox="0 0 640 320" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <rect x="24.5" y="24.5" width="591" height="271" rx="11.5" fill="#F4F4F5" stroke="#E4E4E7"/>
                    <path fill-rule="evenodd" clip-rule="evenodd" d="M48 160C48 146.7 58.7 136 72 136H200V184H72Z" \
                    fill="#18181B"/>
                    <path d="M300 160H340" stroke="rgb(124 58 237 / 80%)" stroke-width="2" stroke-dasharray="6 5"/>
                    </svg>
                    """, """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!-- Created with Inkscape (http://www.inkscape.org/) -->
                    <svg width="640" height="320" version="1.1" viewBox="0 0 640 320" \
                    xmlns="http://www.w3.org/2000/svg">
                     <g transform="translate(-12.5,4.25) rotate(-2 320 160)">
                      <polyline points="40,40 80,60 120,40" fill="none" stroke="#000" stroke-miterlimit="10"/>
                      <text x="40" y="120" fill="rgb(51, 51, 51)" font-size="16px" font-weight="600" \
                    letter-spacing="-.02em" xml:space="preserve"><tspan x="40" y="120">Label</tspan></text>
                     </g>
                    </svg>
                    """})
        void shouldAcceptTheVisualWhenADrawingToolExportedItWithoutIdsOrStyles(String exported) {
            assertThatCode(() -> PracticeGuidanceRules.validate(new PracticeVisual(exported, "A label")))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "<script>alert(1)</script>",
                    "<foreignObject><div xmlns=\"http://www.w3.org/1999/xhtml\">x</div></foreignObject>",
                    "<style>body { display: none }</style>",
                    "<image href=\"https://example.com/track.png\"/>",
                    "<use href=\"#a\"/>",
                    "<a href=\"javascript:alert(1)\"><rect width=\"1\" height=\"1\"/></a>",
                    "<defs><linearGradient/></defs>",
                    "<animate attributeName=\"x\"/>"
                })
        void shouldRefuseTheVisualWhenAnElementIsNotAShapeOrText(String body) {
            assertThatThrownBy(() -> PracticeGuidanceRules.validate(new PracticeVisual(svg(body), "x")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("The visual uses <")
                    .hasMessageContaining("shapes and text");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "<rect onload=\"alert(1)\" width=\"1\" height=\"1\"/>",
                    "<rect style=\"fill: red\" width=\"1\" height=\"1\"/>",
                    "<rect id=\"a\" width=\"1\" height=\"1\"/>",
                    "<rect xmlns:xlink=\"http://www.w3.org/1999/xlink\" xlink:href=\"#a\" width=\"1\" height=\"1\"/>",
                    "<rect href=\"#a\" width=\"1\" height=\"1\"/>"
                })
        void shouldRefuseTheVisualWhenAnAttributeIsAScriptALinkAnIdOrAStyle(String body) {
            assertThatThrownBy(() -> PracticeGuidanceRules.validate(new PracticeVisual(svg(body), "x")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("uses the attribute");
        }

        @Test
        void shouldRefuseTheVisualWhenAPaintReachesOutsideIt() {
            String body = "<rect fill=\"url(https://example.com/a.svg#p)\" width=\"1\" height=\"1\"/>";
            assertThatThrownBy(() -> PracticeGuidanceRules.validate(new PracticeVisual(svg(body), "x")))
                    .hasMessageContaining("refers to something outside itself in “fill”");
        }

        @Test
        void shouldRefuseTheVisualWhenAnEscapeCouldSpellAReference() {
            String body = "<rect fill=\"\\75 rl(https://example.com/a.svg#p)\" width=\"1\" height=\"1\"/>";
            assertThatThrownBy(() -> PracticeGuidanceRules.validate(new PracticeVisual(svg(body), "x")))
                    .hasMessageContaining("uses a character Hephaestus does not accept in “fill”");
        }

        @Test
        void shouldAcceptTheStarterTemplateThatTheAdminDocsOffer() throws IOException {
            String template = Files.readString(Path.of("../../docs/static/practice-visual-template.svg"));
            assertThatCode(() -> PracticeGuidanceRules.validate(new PracticeVisual(template, "Starter")))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "<!--><g/><rect width=\"1\" height=\"1\"/>-->",
                    "<text x=\"0\" y=\"5\"><![CDATA[Hi]]></text>",
                    "<?xml-stylesheet href=\"https://example.com/a.css\"?>"
                })
        void shouldRefuseTheVisualWhenAnHtmlParserCouldReadItsMarkupDifferently(String body) {
            assertThatThrownBy(() -> PracticeGuidanceRules.validate(new PracticeVisual(svg(body), "x")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("contains markup a picture cannot use");
        }

        @Test
        void shouldRefuseTheVisualWhenAProcessingInstructionStandsBesideTheRoot() {
            String styled = "<?xml-stylesheet href=\"https://example.com/a.css\"?>" + SQUARE;
            assertThatThrownBy(() -> PracticeGuidanceRules.validate(new PracticeVisual(styled, "x")))
                    .hasMessageContaining("contains markup a picture cannot use");
        }

        @Test
        void shouldRefuseTheVisualWhenItDeclaresADoctype() {
            String withEntity = "<!DOCTYPE svg [<!ENTITY secret SYSTEM \"file:///etc/passwd\">]>"
                    + svg("<text x=\"0\" y=\"5\">&secret;</text>");
            assertThatThrownBy(() -> PracticeGuidanceRules.validate(new PracticeVisual(withEntity, "x")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("is not valid SVG markup");
        }

        @Test
        void shouldRefuseTheMarkupWhenItIsNotAnSvgInTheSvgNamespace() {
            assertThatThrownBy(
                            () -> PracticeGuidanceRules.validate(new PracticeVisual("<svg viewBox=\"0 0 1 1\"/>", "x")))
                    .hasMessageContaining("is not an SVG");
            assertThatThrownBy(() -> PracticeGuidanceRules.validate(
                            new PracticeVisual("<html xmlns=\"http://www.w3.org/2000/svg\"/>", "x")))
                    .hasMessageContaining("is not an SVG");
            assertThatThrownBy(() -> PracticeGuidanceRules.validate(new PracticeVisual("not markup", "x")))
                    .hasMessageContaining("is not valid SVG markup");
        }

        @Test
        void shouldRefuseTheVisualWhenItHasNoViewBox() {
            assertThatThrownBy(() -> PracticeGuidanceRules.validate(new PracticeVisual(
                            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"/>", "x")))
                    .hasMessageContaining("has no viewBox");
        }

        @Test
        void shouldRefuseTheVisualWhenItIsLargerThanTheLimit() {
            String large = svg("<text x=\"0\" y=\"5\">" + "a".repeat(PracticeGuidanceRules.MAX_SVG_BYTES) + "</text>");
            assertThatThrownBy(() -> PracticeGuidanceRules.validate(new PracticeVisual(large, "x")))
                    .hasMessageContaining("larger than 64 KB");
        }

        @Test
        void shouldRefuseTheVisualWhenItsDescriptionIsBlankOrTooLong() {
            assertThatThrownBy(() -> PracticeGuidanceRules.validate(new PracticeVisual(SQUARE, "  ")))
                    .hasMessageContaining("Describe what the visual shows");
            assertThatThrownBy(() -> PracticeGuidanceRules.validate(
                            new PracticeVisual(SQUARE, "a".repeat(PracticeGuidanceRules.MAX_ALT_LENGTH + 1))))
                    .hasMessageContaining("Shorten the visual's description");
        }
    }

    @Nested
    class Guide {

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "## How to do it\n\n![A square](figures/square.svg \"Square\")\n\n"
                            + "[Small CLs](https://google.github.io/eng-practices/)",
                    "![A square][square]\n\n[square]: figures/square.svg"
                })
        void shouldAcceptTheGuideWhenItShowsItsOwnFiguresAndLinksToWebPages(String markdown) {
            var guide = new PracticeGuide(markdown, Map.of("square", SQUARE));
            assertThatCode(() -> PracticeGuidanceRules.validate(guide)).doesNotThrowAnyException();
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "![Tracker](https://example.com/pixel.png)",
                    "![Tracker][pixel]\n\n[pixel]: https://example.com/pixel.png"
                })
        void shouldRefuseTheGuideWhenItShowsAnImageFromOutsideTheGuide(String markdown) {
            assertThatThrownBy(() -> PracticeGuidanceRules.validate(new PracticeGuide(markdown, Map.of())))
                    .hasMessageContaining("A guide shows only its own figures");
        }

        @ParameterizedTest
        @ValueSource(strings = {"<img src=\"https://example.com/pixel.png\">", "Text <b>bold</b>."})
        void shouldRefuseTheGuideWhenItContainsHtml(String markdown) {
            assertThatThrownBy(() -> PracticeGuidanceRules.validate(new PracticeGuide(markdown, Map.of())))
                    .hasMessage("The guide uses HTML. Write it in Markdown only.");
        }

        @ParameterizedTest
        @ValueSource(strings = {"[Run](javascript:alert(1))", "[Home](/settings)", "<mailto:a@example.com>"})
        void shouldRefuseTheGuideWhenALinkIsNotAWebPage(String markdown) {
            assertThatThrownBy(() -> PracticeGuidanceRules.validate(new PracticeGuide(markdown, Map.of())))
                    .hasMessageContaining("Link only to web pages");
        }

        @Test
        void shouldRefuseTheGuideWhenItShowsAFigureItDoesNotHave() {
            var guide = new PracticeGuide("![A square](figures/square.svg)", Map.of());
            assertThatThrownBy(() -> PracticeGuidanceRules.validate(guide)).hasMessageContaining("has no such figure");
        }

        @Test
        void shouldRefuseTheGuideWhenAFigureNameIsNotLowercaseWithHyphens() {
            var guide = new PracticeGuide("Text only.", Map.of("Split_Order", SQUARE));
            assertThatThrownBy(() -> PracticeGuidanceRules.validate(guide))
                    .hasMessageContaining("Name the figure “Split_Order”");
        }

        @Test
        void shouldRefuseTheGuideWhenItNeverShowsAFigure() {
            var guide = new PracticeGuide("Text only.", Map.of("square", SQUARE));
            assertThatThrownBy(() -> PracticeGuidanceRules.validate(guide)).hasMessageContaining("never shows");
        }

        @Test
        void shouldRefuseTheGuideWhenAFigureHasNoDescription() {
            var guide = new PracticeGuide("![](figures/square.svg)", Map.of("square", SQUARE));
            assertThatThrownBy(() -> PracticeGuidanceRules.validate(guide))
                    .hasMessageContaining("Describe the figure “square”");
        }

        @Test
        void shouldRefuseTheGuideWhenAFigureBreaksTheSvgRules() {
            var guide =
                    new PracticeGuide("![A script](figures/bad.svg)", Map.of("bad", svg("<script>alert(1)</script>")));
            assertThatThrownBy(() -> PracticeGuidanceRules.validate(guide))
                    .hasMessageStartingWith("The figure “bad” uses <script>");
        }

        @Test
        void shouldRefuseTheGuideWhenItHasMoreFiguresThanTheLimit() {
            var figures = Map.of("a", SQUARE, "b", SQUARE, "c", SQUARE, "d", SQUARE, "e", SQUARE);
            var guide = new PracticeGuide(
                    "![a](figures/a.svg) ![b](figures/b.svg) ![c](figures/c.svg) ![d](figures/d.svg) "
                            + "![e](figures/e.svg)",
                    figures);
            assertThatThrownBy(() -> PracticeGuidanceRules.validate(guide)).hasMessageContaining("at most 4 figures");
        }

        @Test
        void shouldRefuseTheGuideWhenItIsBlankOrTooLong() {
            assertThatThrownBy(() -> PracticeGuidanceRules.validate(new PracticeGuide("  ", Map.of())))
                    .hasMessageContaining("Write the guide, or remove it");
            assertThatThrownBy(() -> PracticeGuidanceRules.validate(
                            new PracticeGuide("a".repeat(PracticeGuidanceRules.MAX_GUIDE_LENGTH + 1), Map.of())))
                    .hasMessageContaining("Shorten the guide");
        }

        @Test
        void shouldPutEachFigureDescriptionInPlaceOfTheImageWhenTheReaderHasNoPictures() {
            assertThat(PracticeGuidanceRules.describeFigures(
                            "Before\n![Three *changes*](figures/a.svg \"A\") and ![Two](figures/b.svg)\nAfter"))
                    .isEqualTo("Before\n(Figure: Three changes) and (Figure: Two)\nAfter");
        }

        @Test
        void shouldNameOnlyTheGuidesOwnFiguresWhenTheMarkdownShowsOtherImagesToo() {
            assertThat(PracticeGuidanceRules.figureNames(
                            "![A](figures/b.svg) ![B][a] ![C](https://example.com/c.svg)\n\n[a]: figures/a.svg"))
                    .containsExactly("a", "b");
        }
    }
}
