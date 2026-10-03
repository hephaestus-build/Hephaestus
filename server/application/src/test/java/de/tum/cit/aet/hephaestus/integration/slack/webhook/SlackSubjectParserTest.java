package de.tum.cit.aet.hephaestus.integration.slack.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Parity with {@code GitHubSubjectParserTest}/{@code GitLabSubjectParserTest}: pins the
 * {@code slack.<team>.<channel>.<event>} → {@link EventTypeKey} contract and the malformed-subject
 * rejection arms of {@link SlackSubjectParser}.
 */
class SlackSubjectParserTest extends BaseUnitTest {

    private final SlackSubjectParser parser = new SlackSubjectParser();

    @Test
    void parsesMessageSubject() {
        EventTypeKey key = parser.parse("slack.T1.C1.message");

        assertThat(key).isEqualTo(new EventTypeKey(IntegrationKind.SLACK, "message"));
    }

    @Test
    void rejoinsTailComponentsWhenEventHasDots() {
        // A subtyped event token carries a dot; the join-loop must preserve every tail component.
        EventTypeKey key = parser.parse("slack.T1.C1.message.changed");

        assertThat(key).isEqualTo(new EventTypeKey(IntegrationKind.SLACK, "message.changed"));
    }

    private static Stream<Arguments> invalidSubjects() {
        return Stream.of(
                Arguments.of("null subject", null, "blank"),
                Arguments.of("blank subject", "   ", "blank"),
                Arguments.of("wrong prefix", "github.a.b.c", "slack."),
                Arguments.of("too few components", "slack.T1.C1", ">= 4"),
                Arguments.of("blank event segment", "slack.T1.C1.", "event segment must not be blank"));
    }

    @ParameterizedTest(name = "{0} throws")
    @MethodSource("invalidSubjects")
    void invalidSubjectThrows(String description, String subject, String expectedMessageFragment) {
        assertThatThrownBy(() -> parser.parse(subject))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedMessageFragment);
    }

    @Test
    void parserIdentifiesAsSlackKind() {
        assertThat(parser.kind()).isEqualTo(IntegrationKind.SLACK);
    }
}
