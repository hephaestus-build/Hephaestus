package de.tum.cit.aet.hephaestus.integration.slack.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackChannelLifecycleService;
import io.nats.client.Message;
import java.nio.charset.StandardCharsets;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Each lifecycle event type routes to the corresponding {@link SlackChannelLifecycleService} decision. */
@Tag("unit")
class SlackChannelLifecycleMessageHandlerTest {

    private final NatsMessageDeserializer deserializer =
            new NatsMessageDeserializer(JsonMapper.builder().build());
    private final SlackChannelLifecycleService lifecycleService = mock(SlackChannelLifecycleService.class);

    private static Stream<Arguments> routing() {
        return Stream.of(
                Arguments.of(
                        "channel_left", (Builder) (svc, de) -> new SlackChannelLeftMessageHandler(svc, de), (Verifier)
                                svc -> verify(svc).onBotRemoved(eq("T1"), any(JsonNode.class))),
                Arguments.of("group_left", (Builder) (svc, de) -> new SlackGroupLeftMessageHandler(svc, de), (Verifier)
                        svc -> verify(svc).onBotRemoved(eq("T1"), any(JsonNode.class))),
                Arguments.of(
                        "channel_archive",
                        (Builder) (svc, de) -> new SlackChannelArchiveMessageHandler(svc, de),
                        (Verifier) svc -> verify(svc).onArchived(eq("T1"), any(JsonNode.class))),
                Arguments.of(
                        "group_archive", (Builder) (svc, de) -> new SlackGroupArchiveMessageHandler(svc, de), (Verifier)
                                svc -> verify(svc).onArchived(eq("T1"), any(JsonNode.class))),
                Arguments.of(
                        "channel_deleted",
                        (Builder) (svc, de) -> new SlackChannelDeletedMessageHandler(svc, de),
                        (Verifier) svc -> verify(svc).onDeleted(eq("T1"), any(JsonNode.class))),
                Arguments.of(
                        "channel_rename",
                        (Builder) (svc, de) -> new SlackChannelRenameMessageHandler(svc, de),
                        (Verifier) svc -> verify(svc).onRenamed(eq("T1"), any(JsonNode.class))),
                Arguments.of(
                        "group_rename", (Builder) (svc, de) -> new SlackGroupRenameMessageHandler(svc, de), (Verifier)
                                svc -> verify(svc).onRenamed(eq("T1"), any(JsonNode.class))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routing")
    void routesToTheLifecycleDecision(String eventType, Builder build, Verifier verifier) {
        AbstractSlackEnvelopeHandler handler = build.apply(lifecycleService, deserializer);

        assertThat(handler.key().eventType()).isEqualTo(eventType);
        handler.onMessage(nats("{\"team_id\":\"T1\",\"event\":{\"type\":\"" + eventType + "\",\"channel\":\"C1\"}}"));

        verifier.check(lifecycleService);
    }

    private interface Builder
            extends BiFunction<SlackChannelLifecycleService, NatsMessageDeserializer, AbstractSlackEnvelopeHandler> {}

    private interface Verifier {
        void check(SlackChannelLifecycleService service);
    }

    private static Message nats(String body) {
        Message message = mock(Message.class);
        when(message.getData()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return message;
    }
}
