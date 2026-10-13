package de.tum.cit.aet.hephaestus.integration.slack.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadMessageRow;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class SlackConversationProjectorTest extends BaseUnitTest {

    private static final long WORKSPACE = 1L;
    private static final long DEVELOPER = 7L;

    @Mock
    private SlackThreadRepository threadRepository;

    @Mock
    private SlackMessageRepository messageRepository;

    @Mock
    private PersonDataCopyRecorder personCopies;

    private SlackConversationProjector projector;

    @BeforeEach
    void setUp() {
        projector =
                new SlackConversationProjector(threadRepository, messageRepository, new ObjectMapper(), personCopies);
    }

    @Test
    void shouldMarkTheDevelopersOwnMessagesAndDateEachOne() {
        threads(thread("C1", "1760000000.000100"));
        when(messageRepository.findThreadMessages(WORKSPACE, "C1", "1760000000.000100", Pageable.unpaged()))
                .thenReturn(List.of(
                        message("1760000000.000100", 9L, "Is the release out?"),
                        message("1760000060.000200", DEVELOPER, "Tomorrow morning.")));

        JsonNode payload = projector.buildPayload(WORKSPACE, DEVELOPER);

        JsonNode conversation = payload.path("conversations").get(0);
        assertThat(conversation
                        .path("messages")
                        .valueStream()
                        .map(m -> m.path("text").asString() + " "
                                + m.path("byYou").asBoolean()))
                .containsExactly("Is the release out? false", "Tomorrow morning. true");
        assertThat(conversation.path("messages").get(1).path("sentAt").asString())
                .isEqualTo(Instant.ofEpochSecond(1_760_000_060L, 200_000L).toString());
        assertThat(conversation.path("truncated").asBoolean()).isFalse();
        assertThat(payload.path("truncated").asBoolean()).isFalse();
        assertThat(payload.path("omittedThreads").asInt()).isZero();
    }

    @Test
    void shouldKeepTheNewestMessagesOfALongThreadAndSayItWasCut() {
        threads(thread("C1", "1760000000.000000"));
        List<SlackThreadMessageRow> rows = new ArrayList<>();
        for (int i = 0; i < SlackConversationProjector.MAX_MESSAGES_PER_THREAD + 5; i++) {
            rows.add(message("17600000%02d.000000".formatted(i), 9L, "message " + i));
        }
        when(messageRepository.findThreadMessages(eq(WORKSPACE), eq("C1"), anyString(), any(Pageable.class)))
                .thenReturn(rows);

        JsonNode conversation = projector
                .buildPayload(WORKSPACE, DEVELOPER)
                .path("conversations")
                .get(0);

        assertThat(conversation.path("messages")).hasSize(SlackConversationProjector.MAX_MESSAGES_PER_THREAD);
        assertThat(conversation.path("messages").get(0).path("text").asString()).isEqualTo("message 5");
        assertThat(conversation.path("messageCount").asInt())
                .isEqualTo(SlackConversationProjector.MAX_MESSAGES_PER_THREAD + 5);
        assertThat(conversation.path("truncated").asBoolean()).isTrue();
    }

    @Test
    void shouldLeaveOutTheOldestWholeThreadsWhenTheFileIsTooLargeAndCountThem() {
        threads(
                thread("C1", "1760000300.000000"),
                thread("C2", "1760000200.000000"),
                thread("C3", "1760000100.000000"));
        String longText = "x".repeat(SlackConversationProjector.MAX_PAYLOAD_CHARS / 2);
        when(messageRepository.findThreadMessages(eq(WORKSPACE), anyString(), anyString(), any(Pageable.class)))
                .thenAnswer(call -> List.of(message(call.getArgument(2), 9L, longText)));

        JsonNode payload = projector.buildPayload(WORKSPACE, DEVELOPER);

        assertThat(payload.path("conversations")
                        .valueStream()
                        .map(c -> c.path("channel").asString()))
                .containsExactly("C1");
        assertThat(payload.path("omittedThreads").asInt()).isEqualTo(2);
        assertThat(payload.path("truncated").asBoolean()).isTrue();
    }

    @Test
    void shouldFitOneThreadTooLargeForTheFileByLeavingOutItsOldestMessagesCounted() {
        threads(thread("C1", "1760000000.000000"));
        List<SlackThreadMessageRow> rows = new ArrayList<>();
        for (int i = 0; i < SlackConversationProjector.MAX_MESSAGES_PER_THREAD; i++) {
            rows.add(message("17600000%02d.000000".formatted(i), 9L, i + " " + "x".repeat(6_000)));
        }
        when(messageRepository.findThreadMessages(eq(WORKSPACE), eq("C1"), anyString(), any(Pageable.class)))
                .thenReturn(rows);

        JsonNode payload = projector.buildPayload(WORKSPACE, DEVELOPER);

        assertThat(new ObjectMapper().writeValueAsString(payload))
                .hasSizeLessThanOrEqualTo(SlackConversationProjector.MAX_PAYLOAD_CHARS);
        JsonNode thread = payload.path("conversations").get(0);
        assertThat(thread.path("omittedMessages").asInt()
                        + thread.path("messages").size())
                .isEqualTo(SlackConversationProjector.MAX_MESSAGES_PER_THREAD);
        assertThat(thread.path("messages")
                        .get(thread.path("messages").size() - 1)
                        .path("text")
                        .asString())
                .as("the newest messages stay")
                .startsWith((SlackConversationProjector.MAX_MESSAGES_PER_THREAD - 1) + " ");
        assertThat(payload.path("truncated").asBoolean()).isTrue();
    }

    @Test
    void shouldShortenTheOneMessageLeftWhenItAloneIsTooLargeAndSaySo() {
        threads(thread("C1", "1760000000.000000"));
        String huge = "y".repeat(SlackConversationProjector.MAX_PAYLOAD_CHARS * 2);
        when(messageRepository.findThreadMessages(eq(WORKSPACE), eq("C1"), anyString(), any(Pageable.class)))
                .thenReturn(List.of(message("1760000000.000000", 9L, huge)));

        JsonNode payload = projector.buildPayload(WORKSPACE, DEVELOPER);

        assertThat(new ObjectMapper().writeValueAsString(payload))
                .hasSizeLessThanOrEqualTo(SlackConversationProjector.MAX_PAYLOAD_CHARS);
        JsonNode message = payload.path("conversations").get(0).path("messages").get(0);
        assertThat(message.path("textShortened").asBoolean()).isTrue();
        assertThat(message.path("textChars").asInt()).isEqualTo(huge.length());
        assertThat(payload.path("truncated").asBoolean()).isTrue();
    }

    private void threads(Object[]... rows) {
        when(threadRepository.findParticipatingThreadRows(WORKSPACE, DEVELOPER, SlackConversationProjector.MAX_THREADS))
                .thenReturn(List.of(rows));
    }

    private static Object[] thread(String channel, String threadTs) {
        return new Object[] {channel, "general", threadTs, 1L};
    }

    private static SlackThreadMessageRow message(String ts, @Nullable Long authorMemberId, String text) {
        return new SlackThreadMessageRow(ts, "T1", "U" + authorMemberId, authorMemberId, null, null, text, null);
    }
}
