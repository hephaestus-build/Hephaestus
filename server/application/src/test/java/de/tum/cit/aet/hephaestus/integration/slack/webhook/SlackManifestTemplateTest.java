package de.tum.cit.aet.hephaestus.integration.slack.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackAssistantEventHandler;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackChannelJoinNoticeHandler;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackChannelLifecycleService;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackIngestService;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackMentorService;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackUninstallService;
import de.tum.cit.aet.hephaestus.integration.slack.onboarding.SlackAppHomeService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class SlackManifestTemplateTest extends BaseUnitTest {

    private static final Path MANIFEST = Path.of("..", "..", "docs", "admin", "slack-app-manifest-template.yml");

    @Test
    void manifestUsesAgentViewEventsNotLegacyAssistantViewEvents() throws Exception {
        String manifest = Files.readString(MANIFEST, StandardCharsets.UTF_8);

        assertThat(manifest)
                .contains("agent_view:")
                .contains("agent_description:")
                .contains("- app_home_opened")
                .contains("- app_context_changed")
                .contains("- message.im")
                .doesNotContain("- message.app_home")
                .doesNotContain("assistant_view:")
                .doesNotContain("assistant_thread_started")
                .doesNotContain("assistant_thread_context_changed")
                .doesNotContain("latest pull request")
                .doesNotContain("most recent pull request")
                .contains("What needs attention?"); // suggested-prompts anchor
    }

    /**
     * The manifest's {@code bot_events} list and the registered NATS handlers must describe the same event set:
     * a subscribed event with no handler is silently dropped traffic; a handler for an unsubscribed event is dead
     * code that "works" in tests and never fires in production. The handled set is derived from the REAL handler
     * classes (their {@code EventTypeKey}), so adding either side without the other fails here.
     *
     * <p>Mapping quirk (see {@code SlackSubjectKeyDeriver.eventType}): the manifest subscribes message events per
     * surface — {@code message.channels}/{@code message.groups} both arrive as handler event type {@code message},
     * and {@code message.im} arrives as {@code message_im}.
     */
    @Test
    void manifestBotEventsMatchTheRegisteredHandlersExactly() throws Exception {
        Set<String> handled = handlerEventTypes();
        // handler event type -> manifest subscriptions
        Set<String> expectedManifestEvents = new LinkedHashSet<>();
        for (String eventType : handled) {
            switch (eventType) {
                case "message" -> expectedManifestEvents.addAll(List.of("message.channels", "message.groups"));
                case "message_im" -> expectedManifestEvents.add("message.im");
                default -> expectedManifestEvents.add(eventType);
            }
        }

        assertThat(manifestBotEvents()).containsExactlyInAnyOrderElementsOf(expectedManifestEvents);
    }

    private static Set<String> handlerEventTypes() {
        NatsMessageDeserializer deserializer = mock(NatsMessageDeserializer.class);
        SlackChannelLifecycleService lifecycle = mock(SlackChannelLifecycleService.class);
        SlackUninstallService uninstall = mock(SlackUninstallService.class);
        List<IntegrationMessageHandler> handlers = List.of(
                new SlackChannelMessageHandler(
                        mock(SlackIngestService.class), deserializer, mock(TransactionTemplate.class)),
                new SlackMentorDmMessageHandler(mock(SlackMentorService.class), deserializer),
                new SlackAppHomeOpenedMessageHandler(
                        mock(SlackAppHomeService.class),
                        mock(SlackAssistantEventHandler.class),
                        mock(SlackMentorService.class),
                        deserializer),
                new SlackMemberJoinedChannelMessageHandler(mock(SlackChannelJoinNoticeHandler.class), deserializer),
                new SlackAppContextChangedMessageHandler(deserializer),
                new SlackAppUninstalledMessageHandler(uninstall, deserializer),
                new SlackTokensRevokedMessageHandler(uninstall, deserializer),
                new SlackChannelLeftMessageHandler(lifecycle, deserializer),
                new SlackGroupLeftMessageHandler(lifecycle, deserializer),
                new SlackChannelArchiveMessageHandler(lifecycle, deserializer),
                new SlackGroupArchiveMessageHandler(lifecycle, deserializer),
                new SlackChannelDeletedMessageHandler(lifecycle, deserializer),
                new SlackChannelRenameMessageHandler(lifecycle, deserializer),
                new SlackGroupRenameMessageHandler(lifecycle, deserializer));
        return handlers.stream().map(h -> h.key().eventType()).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** String-anchored parse of the {@code bot_events:} block (indented {@code - <event>} lines until dedent). */
    private static List<String> manifestBotEvents() throws Exception {
        String manifest = Files.readString(MANIFEST, StandardCharsets.UTF_8);
        int start = manifest.indexOf("bot_events:");
        assertThat(start).as("manifest declares a bot_events block").isPositive();
        return manifest.substring(start + "bot_events:".length())
                .lines()
                .dropWhile(String::isBlank) // the remainder of the "bot_events:" line itself
                .takeWhile(line -> line.stripLeading().startsWith("- "))
                .map(line -> line.strip().substring(2).strip())
                .toList();
    }
}
