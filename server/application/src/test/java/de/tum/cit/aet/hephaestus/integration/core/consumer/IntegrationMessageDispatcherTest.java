package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandlerRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SubjectParser;
import de.tum.cit.aet.hephaestus.integration.scm.github.webhook.GitHubSubjectParser;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook.GitLabSubjectParser;
import de.tum.cit.aet.hephaestus.integration.slack.webhook.SlackSubjectParser;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.nats.client.Message;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IntegrationMessageDispatcherTest extends BaseUnitTest {

    private static final List<SubjectParser> ALL_PARSERS =
            List.of(new GitHubSubjectParser(), new GitLabSubjectParser(), new SlackSubjectParser());

    @Test
    void githubSubjectWithoutHandlerReturnsEmpty() {
        IntegrationMessageDispatcher dispatcher =
                new IntegrationMessageDispatcher(new IntegrationMessageHandlerRegistry(List.of()), ALL_PARSERS);

        assertThat(dispatcher.dispatch("github.acme.foo.issues")).isEmpty();
    }

    @Test
    void slackSubjectWithoutHandlerReturnsEmpty() {
        IntegrationMessageDispatcher dispatcher =
                new IntegrationMessageDispatcher(new IntegrationMessageHandlerRegistry(List.of()), ALL_PARSERS);

        assertThat(dispatcher.dispatch("slack.T0123.C456.message")).isEmpty();
    }

    @Test
    void gitlabSubjectWithoutHandlerReturnsEmpty() {
        IntegrationMessageDispatcher dispatcher =
                new IntegrationMessageDispatcher(new IntegrationMessageHandlerRegistry(List.of()), ALL_PARSERS);

        assertThat(dispatcher.dispatch("gitlab.acme~group.project.push")).isEmpty();
    }

    @Test
    void unknownPrefixReturnsEmpty() {
        IntegrationMessageDispatcher dispatcher =
                new IntegrationMessageDispatcher(new IntegrationMessageHandlerRegistry(List.of()), ALL_PARSERS);

        // All collapse to empty before any parser is consulted — the allow-list never reflects on input.
        assertThat(dispatcher.dispatch("bitbucket.foo")).isEmpty();
        assertThat(dispatcher.dispatch("bitbucket")).isEmpty();
        assertThat(dispatcher.dispatch("")).isEmpty();
        assertThat(dispatcher.dispatch("github")).isEmpty();
        assertThat(dispatcher.dispatch(".github.acme.foo.issues")).isEmpty();
    }

    @Test
    void malformedSubjectForKnownPrefixReturnsEmpty() {
        IntegrationMessageDispatcher dispatcher =
                new IntegrationMessageDispatcher(new IntegrationMessageHandlerRegistry(List.of()), ALL_PARSERS);

        // The prefix matches, but the parser rejects the structure — the dispatcher
        // must absorb the IllegalArgumentException and return empty so the consumer
        // can ACK rather than crash.
        assertThat(dispatcher.dispatch("github.acme.foo")).isEmpty(); // < 4 components
        assertThat(dispatcher.dispatch("slack.only.three")).isEmpty();
    }

    @Test
    void dispatchResolvesRegisteredHandler() {
        RecordingHandler handler = new RecordingHandler(new EventTypeKey(IntegrationKind.GITHUB, "repository.issues"));
        IntegrationMessageDispatcher dispatcher =
                new IntegrationMessageDispatcher(new IntegrationMessageHandlerRegistry(List.of(handler)), ALL_PARSERS);

        Optional<IntegrationMessageHandler> resolved = dispatcher.dispatch("github.acme.foo.issues");
        assertThat(resolved).contains(handler);
    }

    @Test
    void dispatchSkipsHandlerThatDeclaresItselfDisabled() {
        // A handler whose feature flag is off reports isEnabled()=false; the dispatcher must
        // treat it as no handler so the consumer ACKs and skips.
        RecordingHandler disabled =
                new RecordingHandler(new EventTypeKey(IntegrationKind.GITHUB, "repository.issues"), false);
        IntegrationMessageDispatcher dispatcher =
                new IntegrationMessageDispatcher(new IntegrationMessageHandlerRegistry(List.of(disabled)), ALL_PARSERS);

        assertThat(dispatcher.dispatch("github.acme.foo.issues")).isEmpty();
    }

    @Test
    void duplicateSubjectParserForSameKindFailsAtConstruction() {
        SubjectParser first = new GitHubSubjectParser();
        SubjectParser second = new GitHubSubjectParser();
        IntegrationMessageHandlerRegistry emptyRegistry = new IntegrationMessageHandlerRegistry(List.of());

        assertThatThrownBy(() -> new IntegrationMessageDispatcher(emptyRegistry, List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate SubjectParser")
                .hasMessageContaining("GITHUB")
                .hasMessageContaining(GitHubSubjectParser.class.getName());
    }

    private static class RecordingHandler implements IntegrationMessageHandler {

        private final EventTypeKey key;
        private final boolean enabled;

        RecordingHandler(EventTypeKey key) {
            this(key, true);
        }

        RecordingHandler(EventTypeKey key, boolean enabled) {
            this.key = key;
            this.enabled = enabled;
        }

        @Override
        public EventTypeKey key() {
            return key;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void onMessage(Message msg) {
            // No-op: these tests assert on dispatch resolution, not message handling.
        }
    }
}
