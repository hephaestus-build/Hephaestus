package de.tum.cit.aet.hephaestus.integration.scm.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProviderMentionsTest extends BaseUnitTest {

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '"',
            textBlock = """
            Ask @alice first.                               | Ask `@alice` first.
            @all of you                                     | `@all` of you
            Tell @org/reviewers and @group/sub/team now     | Tell `@org/reviewers` and `@group/sub/team` now
            *@alice* and (@bob)                             | *`@alice`* and (`@bob`)
            [@alice](https://example.test/alice)            | [`@alice`](https://example.test/alice)
            \\@alice                                        | `@alice`
            "<details><summary>Ask @alice</summary>"        | "<details><summary>Ask <code>@alice</code></summary>"
            """)
    void shouldTurnEveryMentionIntoCode(String body, String expected) {
        assertThat(ProviderMentions.escape(body)).isEqualTo(expected);
        assertThat(ProviderMentions.escape(expected)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '"',
            textBlock = """
            Mail a@b.example about it.
            Run `@alice` in code.
            See https://medium.example/@alice/post for more.
            "<details><summary><code>@alice</code></summary>"
            <!-- hephaestus-feedback-copy:1 -->
            """)
    void shouldLeaveTextThatMentionsNoOne(String body) {
        assertThat(ProviderMentions.escape(body)).isEqualTo(body);
    }

    @Test
    void shouldLeaveACodeBlockAsWritten() {
        String body = "Before @alice\n\n```\n@Override\nvoid run() {}\n```\n";
        assertThat(ProviderMentions.escape(body)).isEqualTo("Before `@alice`\n\n```\n@Override\nvoid run() {}\n```\n");
    }
}
