package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.core.exception.AccessForbiddenException;
import de.tum.cit.aet.hephaestus.integration.core.events.BotCommandReceivedEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** A refused review command is logged by what refused it, never by the refusal's own text. */
@Tag("unit")
class ReviewCommandListenerLoggingTest {

    private static final String CANARY = "private-refusal-canary@example.test";

    @Test
    void shouldLogTheRefusalTypeAndIdsWithoutTheExceptionMessage() {
        ReviewRequestService requests = mock(ReviewRequestService.class);
        when(requests.requestByComment(anyLong(), anyLong(), any(), any(), any()))
                .thenThrow(new AccessForbiddenException("Refused for " + CANARY));
        ReviewCommandListener listener = new ReviewCommandListener(
                requests, List.of(), mock(ReviewRepository.class), mock(ReviewItemRepository.class));

        Logger logger = (Logger) LoggerFactory.getLogger(ReviewCommandListener.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            listener.on(new BotCommandReceivedEvent(
                    IntegrationKind.GITHUB, 11L, 22L, "/hephaestus review", "someone", 1L, 33L, 44L, null));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        List<ILoggingEvent> refusals = appender.list.stream()
                .filter(event -> event.getMessage().startsWith("Review command refused"))
                .toList();
        assertThat(refusals).hasSize(1);
        ILoggingEvent refusal = refusals.getFirst();
        assertThat(refusal.getFormattedMessage())
                .contains("workspaceId=11")
                .contains("itemId=22")
                .contains(AccessForbiddenException.class.getName())
                .doesNotContain(CANARY);
        assertThat(Arrays.toString(refusal.getArgumentArray())).doesNotContain(CANARY);
        assertThat(refusal.getThrowableProxy()).isNull();
    }
}
