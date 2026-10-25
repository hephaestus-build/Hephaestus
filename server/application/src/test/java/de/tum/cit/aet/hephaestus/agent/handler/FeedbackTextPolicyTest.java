package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.FeedbackTextPolicy.Rule;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedProblemSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedResultSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceCitation;
import de.tum.cit.aet.hephaestus.practices.spi.ProblemCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEvidence;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class FeedbackTextPolicyTest extends BaseUnitTest {
    private static final String SUPPLIED = "https://docs.example.org/export#failures";
    private static final String PROBLEM_SUPPLIED = "https://en.wikipedia.org/wiki/Retry_(computing)";
    private static final String SUMMARY_ONLY = "https://summary.example.org/";

    private final FeedbackTextPolicy policy = new FeedbackTextPolicy(List.of(admitted()));

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Keep the failure cause when the export handler returns.",
                "Add `@Override` to `close()` and keep `<T extends Closeable>` as declared.",
                "```java\n@Transactional\nvoid export() { call(\"https://api.example.com\"); }\n```",
                "Ask the owner at ops@example.com before you change the retry.",
                "Mention the [export guide](" + SUPPLIED + ") that the description already links.",
                "The description links " + SUPPLIED + ".",
                "Rename `List<@Nullable String>` only where it reads better.",
                "Thanks for the clear test \uD83D\uDC69\u200D\uD83D\uDCBB.",
                "Use **bold** sparingly, and a list:\n\n- first\n- second",
                "Retries follow [the article](" + PROBLEM_SUPPLIED + ") that the change cites.",
                "Retries follow the cited article (" + PROBLEM_SUPPLIED + ")."
            })
    void shouldPermitPlainFeedbackCodeAndSuppliedLinks(String text) {
        assertThat(policy.broken(text)).isNull();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            See [the fix](https://other.example.net/fix).                 | LINK
            See https://other.example.net/fix for the fix.                | LINK
            See www.other.example.net for the fix.                        | LINK
            See <https://other.example.net/fix>.                          | LINK
            See _https://other.example.net/fix for the fix.               | LINK
            See *www.other.example.net* for the fix.                      | LINK
            See [the guide](./docs/guide.md).                             | LINK
            Ask [the owner](mailto:owner@example.com).                    | LINK
            Read [https://docs.example.org/export#failures](https://x.io) | LINK
            ![diagram](https://docs.example.org/export#failures)          | IMAGE
            <details><summary>More</summary>Hidden words.</details>       | HTML
            Keep the cause. <!-- hidden words -->                         | HTML
            Keep the cause.<img src=x>                                    | HTML
            Return a List<String> from the handler.                       | HTML
            Ask @octocat to review.                                       | MENTION
            Add @Override to close() so the compiler checks it.           | MENTION
            Ask @octo-org/reviewers to review.                            | MENTION
            Ask @group/subgroup to review.                                | MENTION
            Ask (@all) to review.                                         | MENTION
            Ask \\@octocat to review.                                     | MENTION
            Ask &#64;octocat to review.                                   | MENTION
            Ask *@octocat* to review.                                     | MENTION
            Ask <@U012AB3CD> to review.                                   | MENTION
            Ask `<@U012AB3CD>` to review.                                 | MENTION
            Tell <!here> about it.                                        | MENTION
            Tell <!channel> about it.                                     | MENTION
            Tell <!subteam^S012AB3CD> about it.                           | MENTION
            Tell <#C012AB3CD> about it.                                   | MENTION
            """)
    void shouldRefuseLinksMarkupAndMentionsThatTheInputDidNotSupply(String text, Rule rule) {
        assertThat(policy.broken(text)).isEqualTo(rule);
    }

    @ParameterizedTest
    @ValueSource(ints = {0x202E, 0x2066, 0x200B, 0x200E, 0xFEFF, 0x00AD, 0xE0041, 0x0007, 0x2028})
    void shouldRefuseHiddenAndBidirectionalCharacters(int codePoint) {
        assertThat(policy.broken("Keep the cause" + Character.toString(codePoint) + " in the error."))
                .isEqualTo(Rule.HIDDEN_CHARACTER);
    }

    @Test
    void shouldReadSuppliedLinksOnlyFromCitedQuotes() {
        assertThat(policy.broken("The result summary links " + SUMMARY_ONLY + "."))
                .isEqualTo(Rule.LINK);
        assertThat(new FeedbackTextPolicy(List.of()).broken("The description links " + SUPPLIED + "."))
                .isEqualTo(Rule.LINK);
    }

    @Test
    void shouldMatchASuppliedLinkExactlySoATrailingSlashIsAnotherLink() {
        assertThat(policy.broken("See [the export guide](https://docs.example.org/export/#failures)."))
                .isEqualTo(Rule.LINK);
        assertThat(policy.broken("See https://en.wikipedia.org/wiki/Retry_(computing)/ for retries."))
                .isEqualTo(Rule.LINK);
    }

    private static AdmittedResultSnapshot admitted() {
        var evidence = new ResultEvidence(
                List.of(quote("Failures follow the [export guide](" + SUPPLIED + ").")), null, null, List.of());
        var candidate = mock(ResultCandidate.class);
        when(candidate.summary()).thenReturn("The export handler drops the failure cause, see " + SUMMARY_ONLY);
        when(candidate.evidence()).thenReturn(evidence);
        var problem = mock(ProblemCandidate.class);
        when(problem.citations()).thenReturn(List.of(quote("Retries follow " + PROBLEM_SUPPLIED + ".")));
        return new AdmittedResultSnapshot(
                UUID.randomUUID(), candidate, List.of(new AdmittedProblemSnapshot(UUID.randomUUID(), problem)));
    }

    private static EvidenceCitation quote(String quote) {
        return new EvidenceCitation(
                new SourceKind("scm.pull-request.core"),
                "work/pull-request.md",
                "work/pull-request.md",
                null,
                null,
                3,
                3,
                quote,
                null);
    }
}
