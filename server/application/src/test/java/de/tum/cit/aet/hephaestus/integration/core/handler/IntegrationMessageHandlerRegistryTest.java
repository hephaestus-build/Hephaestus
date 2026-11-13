package de.tum.cit.aet.hephaestus.integration.core.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntegrationMessageHandlerRegistryTest extends BaseUnitTest {

    @Test
    void emptyHandlerListProducesEmptyRegistry() {
        IntegrationMessageHandlerRegistry registry = new IntegrationMessageHandlerRegistry(List.of());

        assertThat(registry.handlerCount()).isZero();
        assertThat(registry.resolve(new EventTypeKey(IntegrationKind.GITHUB, "repository.push")))
                .isEmpty();
    }

    @Test
    void singleHandlerIsRegisteredAndResolvable() {
        StubHandler githubPush = new StubHandler(new EventTypeKey(IntegrationKind.GITHUB, "repository.push"));
        IntegrationMessageHandlerRegistry registry = new IntegrationMessageHandlerRegistry(List.of(githubPush));

        assertThat(registry.handlerCount()).isEqualTo(1);
        assertThat(registry.resolve(githubPush.key())).contains(githubPush);
    }

    @Test
    void twoHandlersWithDistinctKeysCoexist() {
        StubHandler githubPush = new StubHandler(new EventTypeKey(IntegrationKind.GITHUB, "repository.push"));
        StubHandler gitlabMr = new StubHandler(new EventTypeKey(IntegrationKind.GITLAB, "merge_request"));
        IntegrationMessageHandlerRegistry registry =
                new IntegrationMessageHandlerRegistry(List.of(githubPush, gitlabMr));

        assertThat(registry.handlerCount()).isEqualTo(2);
        assertThat(registry.resolve(githubPush.key())).contains(githubPush);
        assertThat(registry.resolve(gitlabMr.key())).contains(gitlabMr);
        // Mismatched (kind, eventType) keys never confuse the lookup:
        assertThat(registry.resolve(new EventTypeKey(IntegrationKind.GITHUB, "merge_request")))
                .isEmpty();
        assertThat(registry.resolve(new EventTypeKey(IntegrationKind.GITLAB, "repository.push")))
                .isEmpty();
    }

    @Test
    void duplicateKeyFailsFastWithBothClassNames() {
        EventTypeKey shared = new EventTypeKey(IntegrationKind.SLACK, "message");
        StubHandler first = new StubHandler(shared);
        SecondStubHandler second = new SecondStubHandler(shared);

        assertThatThrownBy(() -> new IntegrationMessageHandlerRegistry(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(shared.toString())
                .hasMessageContaining(StubHandler.class.getName())
                .hasMessageContaining(SecondStubHandler.class.getName());
    }

    @Test
    void resolveWithNullKeyReturnsEmpty() {
        IntegrationMessageHandlerRegistry registry = new IntegrationMessageHandlerRegistry(List.of());

        assertThat(registry.resolve((EventTypeKey) null)).isEmpty();
    }

    private static class StubHandler implements IntegrationMessageHandler {

        private final EventTypeKey key;

        StubHandler(EventTypeKey key) {
            this.key = key;
        }

        @Override
        public EventTypeKey key() {
            return key;
        }

        @Override
        public void onMessage(ProviderEvent msg) {
            // no-op — only key() is exercised in this suite
        }
    }

    /** A distinct class so the duplicate-key message names BOTH offending types. */
    private static class SecondStubHandler implements IntegrationMessageHandler {

        private final EventTypeKey key;

        SecondStubHandler(EventTypeKey key) {
            this.key = key;
        }

        @Override
        public EventTypeKey key() {
            return key;
        }

        @Override
        public void onMessage(ProviderEvent msg) {
            // no-op
        }
    }
}
