package de.tum.cit.aet.hephaestus.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ModelLinksTest {

    private static final Set<String> HOSTS = Set.of("github.com", "hephaestus.example", ".hephaestus.example");

    @Test
    void shouldKeepALinkWhenItPointsToAnAllowedHost() {
        String text = "See [the pull request](https://github.com/acme/app/pull/7) and https://hephaestus.example/w/a.";

        assertThat(ModelLinks.neutralize(text, HOSTS)).isEqualTo(text);
    }

    @Test
    void shouldKeepAWorkspaceAddressWhenItIsASubdomainOfTheHephaestusHost() {
        String text = "Open [your page](https://team.hephaestus.example/profile).";

        assertThat(ModelLinks.neutralize(text, HOSTS)).isEqualTo(text);
    }

    @Test
    void shouldShowTheLabelAndTheAddressAsCodeWhenALinkLeavesTheAllowedHosts() {
        String text = "A [helpful guide](https://evil.example/?q=secret) explains it.";

        assertThat(ModelLinks.neutralize(text, HOSTS))
                .isEqualTo("A helpful guide (`https://evil.example/?q=secret`) explains it.");
    }

    @Test
    void shouldTurnABareAddressIntoCodeWhenItLeavesTheAllowedHosts() {
        assertThat(ModelLinks.neutralize("Read https://evil.example/x?y=1.", HOSTS))
                .isEqualTo("Read `https://evil.example/x?y=1`.");
        assertThat(ModelLinks.neutralize("Read www.evil.example now", HOSTS)).isEqualTo("Read `www.evil.example` now");
    }

    @Test
    void shouldTurnAnAutolinkIntoCodeWhenItLeavesTheAllowedHosts() {
        assertThat(ModelLinks.neutralize("Go to <https://evil.example/a>", HOSTS))
                .isEqualTo("Go to `https://evil.example/a`");
    }

    @Test
    void shouldNotTrustAHostThatOnlyEndsLikeAnAllowedOne() {
        assertThat(ModelLinks.neutralize("[x](https://notgithub.com/a)", HOSTS))
                .isEqualTo("x (`https://notgithub.com/a`)");
        assertThat(ModelLinks.neutralize("[x](https://github.com.evil.example/a)", HOSTS))
                .isEqualTo("x (`https://github.com.evil.example/a`)");
        assertThat(ModelLinks.neutralize("[x](https://user@github.com/a)", HOSTS))
                .isEqualTo("x (`https://user@github.com/a`)");
    }

    @Test
    void shouldTurnAnImageIntoItsAddressAsCode() {
        assertThat(ModelLinks.neutralize("![logo](https://github.com/x.png)", HOSTS))
                .isEqualTo("logo (`https://github.com/x.png`)");
    }

    @Test
    void shouldNeutralizeSlackMentionsAndLinkSyntax() {
        assertThat(ModelLinks.neutralize("Hi <!here> and <@U123>, see <https://evil.example|docs>.", HOSTS))
                .isEqualTo("Hi `<!here>` and `<@U123>`, see `https://evil.example|docs`.");
    }

    @Test
    void shouldLeaveInlineCodeAsWrittenBecauseItRendersAsText() {
        String text = "Run `curl https://evil.example` now.";

        assertThat(ModelLinks.neutralize(text, HOSTS)).isEqualTo(text);
    }

    @Test
    void shouldTurnAnOutsideAddressInACodeBlockIntoCodeBecauseAnotherRendererMayNotReadTheBlock() {
        assertThat(ModelLinks.neutralize("```\nsee https://evil.example/a\n```\n", HOSTS))
                .isEqualTo("```\nsee `https://evil.example/a`\n```\n");
        assertThat(ModelLinks.neutralize("    [x](https://evil.example/a)\n", HOSTS))
                .isEqualTo("    [x](`https://evil.example/a`)\n");
    }

    @Test
    void shouldApplyTheRuleToTheWordsOfALinkThatLeaves() {
        assertThat(ModelLinks.neutralize("[![a](https://evil.example/p.png?q=s)](https://evil2.example/)", HOSTS))
                .isEqualTo("a (`https://evil.example/p.png?q=s`) (`https://evil2.example/`)");
        assertThat(ModelLinks.neutralize("[see https://evil.example/?q=s](https://evil2.example/)", HOSTS))
                .isEqualTo("see `https://evil.example/?q=s` (`https://evil2.example/`)");
    }

    @Test
    void shouldResolveAReferenceLinkAndDefangItsDefinition() {
        assertThat(ModelLinks.neutralize("Read [x][r].\n\n[r]: https://evil.example/?q=s\n", HOSTS))
                .isEqualTo("Read x (`https://evil.example/?q=s`).\n\n[r]: `https://evil.example/?q=s`\n");
    }

    @Test
    void shouldDefangAnAddressInRawHtml() {
        assertThat(ModelLinks.neutralize("<div>\nhttps://evil.example/?q=s\n</div>\n", HOSTS))
                .isEqualTo("<div>\n`https://evil.example/?q=s`\n</div>\n");
    }

    @Test
    void shouldReadALoneCarriageReturnAsALineBreak() {
        assertThat(ModelLinks.neutralize("a\rhttps://evil.example/x", HOSTS)).isEqualTo("a\n`https://evil.example/x`");
    }

    @Test
    void shouldFenceAnAddressThatHoldsABacktick() {
        assertThat(ModelLinks.code("a`b")).isEqualTo("``a`b``");
    }

    @Test
    void shouldRefuseAnAddressThatIsNotAWebAddress() {
        assertThat(ModelLinks.allowed("javascript:alert(1)", HOSTS)).isFalse();
        assertThat(ModelLinks.allowed("mailto:a@github.com", HOSTS)).isFalse();
        assertThat(ModelLinks.allowed("https://GitHub.com/a", HOSTS)).isTrue();
    }
}
