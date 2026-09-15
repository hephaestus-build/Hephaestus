package de.tum.cit.aet.hephaestus.agent.sandbox;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.runtime.AgentImageProperties;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryProperties;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class AgentImagePinGuardTest extends BaseUnitTest {

    private static final String PINNED_AGENT = "ghcr.io/x/agent-pi@sha256:" + "a".repeat(64);
    private static final String PINNED_GIT = "ghcr.io/x/git-preparation@sha256:" + "b".repeat(64);

    private static final String TAGGED_GIT = "ghcr.io/x/git-preparation:0.73.2";

    private static GitRepositoryProperties gitImage(String image) {
        return new GitRepositoryProperties(true, 2, image, 1L << 33);
    }

    private static AgentImagePinGuard guardFor(AgentImageProperties props, String gitImage) {
        return new AgentImagePinGuard(props, gitImage(gitImage), new MockEnvironment());
    }

    @Test
    void shouldAllowStartupWhenBothReferencesAreDigestPinned() {
        var props = new AgentImageProperties(PINNED_AGENT, ImagePullPolicy.ALWAYS);
        assertThatCode(() -> guardFor(props, PINNED_GIT)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ghcr.io/x/agent-pi:0.73.2", "ghcr.io/x/agent-pi@sha256:abc123"})
    void shouldFailFastWhenReferenceIsNotDigestPinned(String reference) {
        var props = new AgentImageProperties(reference, ImagePullPolicy.ALWAYS);
        assertThatThrownBy(() -> guardFor(props, PINNED_GIT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(reference)
                .hasMessageContaining("hephaestus.agent.image.require-digest")
                .hasMessageContaining("docs/admin/release-image-lock.md");
    }

    @Test
    void shouldFailFastWhenTheGitImageIsNotDigestPinnedOnAWorker() {
        var props = new AgentImageProperties(PINNED_AGENT, ImagePullPolicy.ALWAYS);
        assertThatThrownBy(() -> guardFor(props, TAGGED_GIT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hephaestus.git.image")
                .hasMessageContaining(TAGGED_GIT);
    }

    /** The release lock hands the Git image to the server and worker pods; the webhook pod runs no Git. */
    @Test
    void shouldLeaveTheGitImageAloneWhenTheWorkerRoleIsOff() {
        var props = new AgentImageProperties(PINNED_AGENT, ImagePullPolicy.ALWAYS);
        var webhookOnly = new MockEnvironment().withProperty("hephaestus.runtime.worker.enabled", "false");
        assertThatCode(() -> new AgentImagePinGuard(props, gitImage(TAGGED_GIT), webhookOnly))
                .doesNotThrowAnyException();
    }

    /** The reference carries no compiled-in default, so an unresolved one reaches this guard as null. */
    @Test
    void shouldFailFastWhenNoReferenceResolved() throws ReflectiveOperationException {
        var props = AgentImageProperties.class
                .getDeclaredConstructor(String.class, ImagePullPolicy.class)
                .newInstance(null, ImagePullPolicy.ALWAYS);
        assertThatThrownBy(() -> guardFor(props, PINNED_GIT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("<not set>");
    }
}
