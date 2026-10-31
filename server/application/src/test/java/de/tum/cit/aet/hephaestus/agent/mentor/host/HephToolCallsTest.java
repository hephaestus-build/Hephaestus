package de.tum.cit.aet.hephaestus.agent.mentor.host;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnAttempt;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class HephToolCallsTest extends BaseUnitTest {

    @Test
    void shouldLogTheTurnTheToolAndHowTheCallEndedButNothingTheCallCarried() {
        Logger logger = (Logger) LoggerFactory.getLogger(HephToolCalls.class);
        ListAppender<ILoggingEvent> lines = new ListAppender<>();
        lines.start();
        logger.addAppender(lines);
        UUID turnId = UUID.randomUUID();
        TurnAttempt turn = new TurnAttempt(turnId, 7L, 11L, UUID.randomUUID(), 2, UUID.randomUUID());
        try {
            HephToolCalls.logged(turn, "search_conversation", () -> "the developer's private words", found -> "ok");
        } finally {
            logger.detachAppender(lines);
        }

        ILoggingEvent line = lines.list.getFirst();
        Map<String, Object> fields =
                line.getKeyValuePairs().stream().collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
        assertThat(fields)
                .containsEntry("event.name", "heph.turn.tool_call")
                .containsEntry("turn.id", turnId)
                .containsEntry("turn.attempt", 2)
                .containsEntry("gen_ai.tool.name", "search_conversation")
                .containsEntry("outcome", "ok")
                .containsKey("duration.ms");
        assertThat(line.getFormattedMessage() + fields).doesNotContain("private words");
    }
}
