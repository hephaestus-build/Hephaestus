package de.tum.cit.aet.hephaestus.agent.sandbox;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.runtime.AgentImageProperties;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AgentImagePinGuardTest extends BaseUnitTest {

    private static final String PINNED_AGENT = "ghcr.io/x/agent-pi@sha256:" + "a".repeat(64);

    @Test
    void shouldAllowStartupWhenReferenceIsDigestPinned() {
        var props = new AgentImageProperties(PINNED_AGENT, ImagePullPolicy.ALWAYS);
        assertThatCode(() -> new AgentImagePinGuard(props)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ghcr.io/x/agent-pi:0.73.2", "ghcr.io/x/agent-pi@sha256:abc123"})
    void shouldFailFastWhenReferenceIsNotDigestPinned(String reference) {
        var props = new AgentImageProperties(reference, ImagePullPolicy.ALWAYS);
        assertThatThrownBy(() -> new AgentImagePinGuard(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(reference)
                .hasMessageContaining("hephaestus.agent.image.require-digest")
                .hasMessageContaining("docs/admin/release-image-lock.md");
    }

    /** The reference carries no compiled-in default, so an unresolved one reaches this guard as null. */
    @Test
    void shouldFailFastWhenNoReferenceResolved() throws ReflectiveOperationException {
        var props = AgentImageProperties.class
                .getDeclaredConstructor(String.class, ImagePullPolicy.class)
                .newInstance(null, ImagePullPolicy.ALWAYS);
        assertThatThrownBy(() -> new AgentImagePinGuard(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("<not set>");
    }
}
