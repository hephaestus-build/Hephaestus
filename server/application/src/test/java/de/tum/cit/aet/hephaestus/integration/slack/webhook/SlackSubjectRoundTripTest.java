package de.tum.cit.aet.hephaestus.integration.slack.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.consumer.ConsumerSubjectMath;
import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Producer↔consumer subject-grammar agreement for every Slack Events API event we handle. */
class SlackSubjectRoundTripTest extends BaseUnitTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final SlackSubjectKeyDeriver DERIVER = new SlackSubjectKeyDeriver();
    private static final SlackSubjectParser PARSER = new SlackSubjectParser();

    @ParameterizedTest(name = "{1}")
    @MethodSource("events")
    void producerSubjectIsDeliveredToTheExpectedHandler(String body, String expectedSubject, EventTypeKey expectedKey) {
        JsonNode payload = MAPPER.readTree(body);

        String producerSubject = DERIVER.subjectFor(payload);

        assertThat(producerSubject).isEqualTo(expectedSubject);
        assertThat(producerSubject)
                .startsWith(
                        ConsumerSubjectMath.streamNameFor(IntegrationKind.SLACK).orElseThrow() + ".");
        assertThat(ConsumerSubjectMath.kindFromSubjectPrefix(producerSubject)).contains(IntegrationKind.SLACK);
        assertThat(PARSER.parse(producerSubject)).isEqualTo(expectedKey);
        assertThat(producerSubject.split("\\.", -1)).hasSize(4);
    }

    @Test
    void streamAndFilterAreWiredForSlack() {
        assertThat(ConsumerSubjectMath.streamNameFor(IntegrationKind.SLACK)).contains("slack");
        assertThat(ConsumerSubjectMath.teamFilter("slack", "T0ABC123")).isEqualTo("slack.T0ABC123.>");
    }

    @Test
    void slackTeamFilterMatchesPublishedSubjects() {
        // The per-workspace filter must agree with the deriver's publish-side sanitisation (dots -> ~),
        // so a filter built from the stored team id always matches the subjects the receiver publishes.
        JsonNode payload = MAPPER.readTree(
                "{\"type\":\"event_callback\",\"team_id\":\"T.dotted\",\"event\":{\"type\":\"message\",\"channel_type\":\"channel\",\"channel\":\"C1\"}}");
        String subject = DERIVER.deriveSubject(payload, Map.of());
        String filterPrefix = ConsumerSubjectMath.teamFilter("slack", "T.dotted");
        assertThat(filterPrefix).isEqualTo("slack.T~dotted.>");
        assertThat(subject).startsWith("slack.T~dotted.");
    }

    @Test
    void dedupUsesSlackEventIdBeforeHashFallback() {
        JsonNode payload = MAPPER.readTree(
                "{\"type\":\"event_callback\",\"team_id\":\"T1\",\"event_id\":\"Ev9\",\"event\":{\"type\":\"message\",\"channel_type\":\"channel\",\"channel\":\"C1\"}}");

        assertThat(DERIVER.dedupIdFor(payload)).isEqualTo("slack-Ev9");
    }

    static Stream<Arguments> events() {
        return Stream.of(
                Arguments.of(
                        "{\"type\":\"event_callback\",\"team_id\":\"T1\",\"event\":{\"type\":\"message\",\"channel_type\":\"channel\",\"channel\":\"C1\",\"ts\":\"100.1\"}}",
                        "slack.T1.C1.message",
                        new EventTypeKey(IntegrationKind.SLACK, "message")),
                Arguments.of(
                        "{\"type\":\"event_callback\",\"team_id\":\"T.dotted\",\"event\":{\"type\":\"message\",\"channel_type\":\"group\",\"channel\":\"G.dotted\",\"ts\":\"100.1\"}}",
                        "slack.T~dotted.G~dotted.message",
                        new EventTypeKey(IntegrationKind.SLACK, "message")),
                Arguments.of(
                        "{\"type\":\"event_callback\",\"team_id\":\"T1\",\"event\":{\"type\":\"message\",\"channel_type\":\"im\",\"channel\":\"D1\",\"user\":\"U1\"}}",
                        "slack.T1.U1.message_im",
                        new EventTypeKey(IntegrationKind.SLACK, "message_im")),
                Arguments.of(
                        "{\"type\":\"event_callback\",\"team_id\":\"T1\",\"event\":{\"type\":\"app_home_opened\",\"user\":\"U1\"}}",
                        "slack.T1.U1.app_home_opened",
                        new EventTypeKey(IntegrationKind.SLACK, "app_home_opened")),
                Arguments.of(
                        "{\"type\":\"event_callback\",\"team_id\":\"T1\",\"event\":{\"type\":\"app_context_changed\",\"user\":\"U1\",\"context\":{\"entities\":[{\"type\":\"slack#/types/channel_id\",\"value\":\"C1\"}]}}}",
                        "slack.T1.C1.app_context_changed",
                        new EventTypeKey(IntegrationKind.SLACK, "app_context_changed")),
                Arguments.of(
                        "{\"type\":\"event_callback\",\"team_id\":\"T1\",\"event\":{\"type\":\"member_joined_channel\",\"channel\":\"C1\",\"user\":\"U1\"}}",
                        "slack.T1.C1.member_joined_channel",
                        new EventTypeKey(IntegrationKind.SLACK, "member_joined_channel")),
                Arguments.of(
                        "{\"type\":\"event_callback\",\"team_id\":\"T1\",\"event\":{\"type\":\"app_uninstalled\"}}",
                        "slack.T1.workspace.app_uninstalled",
                        new EventTypeKey(IntegrationKind.SLACK, "app_uninstalled")),
                Arguments.of(
                        "{\"type\":\"event_callback\",\"team_id\":\"T1\",\"event\":{\"type\":\"tokens_revoked\"}}",
                        "slack.T1.workspace.tokens_revoked",
                        new EventTypeKey(IntegrationKind.SLACK, "tokens_revoked")));
    }
}
