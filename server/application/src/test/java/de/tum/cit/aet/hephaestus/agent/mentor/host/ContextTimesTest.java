package de.tum.cit.aet.hephaestus.agent.mentor.host;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ContextTimesTest extends BaseUnitTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldGiveASlackMessageItsTimeInTheDevelopersZoneAndLeaveItsWordsAlone() {
        JsonNode slack = mapper.readTree("""
                {"conversations":[{"channel":"C1","threadTs":"1760272860.000100","messages":[
                  {"ts":"1760272860.000100","sentAt":"2026-10-12T12:41:00.000100Z",
                   "text":"2026-10-12T12:41:00Z","byYou":false}]}]}
                """);

        JsonNode message = ContextTimes.local(slack, BERLIN)
                .path("conversations")
                .get(0)
                .path("messages")
                .get(0);

        assertThat(message.path("sentAt").asString()).isEqualTo("2026-10-12 14:41 +02:00");
        assertThat(message.path("ts").asString()).isEqualTo("1760272860.000100");
        assertThat(message.path("text").asString())
                .as("a message is the author's words, even when they are only a time")
                .isEqualTo("2026-10-12T12:41:00Z");
    }

    @Test
    void shouldGiveAnObservationItsTimeAndKeepItsEvidenceQuoteAsCited() {
        JsonNode history = mapper.readTree("""
                {"observation":{"observedAt":"2026-10-12T12:41:00Z","summary":"2026-10-12T12:41:00Z",
                  "evidence":{"citations":[{"path":"CHANGELOG.md","startLine":3,"quote":"2026-10-12T12:41:00Z"}]},
                  "criteria":["Release at 2026-10-12T12:41:00Z"],"tags":["2026-10-12T12:41:00Z"]}}
                """);
        String before = history.path("observation").path("evidence").toString();

        JsonNode observation = ContextTimes.local(history, BERLIN).path("observation");

        assertThat(observation.path("observedAt").asString()).isEqualTo("2026-10-12 14:41 +02:00");
        assertThat(observation.path("evidence").toString()).isEqualTo(before);
        assertThat(observation.path("summary").asString()).isEqualTo("2026-10-12T12:41:00Z");
        assertThat(observation.path("tags").get(0).asString()).isEqualTo("2026-10-12T12:41:00Z");
    }

    @Test
    void shouldGiveEveryActivityTimeItsOffsetAcrossTheChangeToWinterTime() {
        JsonNode activity = mapper.readTree("""
                {"window":{"since":"2026-10-20T06:00:00Z","until":"2026-11-03T06:00:00Z","days":14},
                 "reviewsGiven":[{"submittedAt":"2026-10-24T21:30:00Z","state":"APPROVED"}],
                 "commits":[{"sha":"abc1234","authoredAt":"2026-11-02T23:15:30Z"}]}
                """);

        JsonNode local = ContextTimes.local(activity, BERLIN);

        assertThat(local.path("window").path("since").asString()).isEqualTo("2026-10-20 08:00 +02:00");
        assertThat(local.path("window").path("until").asString()).isEqualTo("2026-11-03 07:00 +01:00");
        assertThat(local.path("reviewsGiven").get(0).path("submittedAt").asString())
                .isEqualTo("2026-10-24 23:30 +02:00");
        assertThat(local.path("commits").get(0).path("authoredAt").asString())
                .as("the next day in the developer's zone")
                .isEqualTo("2026-11-03 00:15 +01:00");
        assertThat(local.path("window").path("days").asInt()).isEqualTo(14);
    }
}
