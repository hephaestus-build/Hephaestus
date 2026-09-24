package de.tum.cit.aet.hephaestus.agent.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.runtime.AgentImageProperties;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

class AgentImageReferenceGuardTest extends BaseUnitTest {

    @ParameterizedTest
    @ValueSource(strings = {"specs", "cds-training"})
    void shouldNotRequireRuntimeImagesDuringArtifactGeneration(String profile) {
        // An inherited digest policy is runtime configuration; an artifact build uses no agent image.
        new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=" + profile, "hephaestus.agent.image.require-digest=true")
                .withUserConfiguration(AgentImageReferenceGuard.class, AgentImagePinGuard.class)
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(AgentImageReferenceGuard.class)
                        .doesNotHaveBean(AgentImagePinGuard.class));
    }

    private static AgentImageReferenceGuard guardFor(String reference) {
        return new AgentImageReferenceGuard(new AgentImageProperties(reference, ImagePullPolicy.IF_NOT_PRESENT));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ghcr.io/hephaestus-build/agent-pi:0.73.2",
                "ghcr.io/hephaestus-build/agent-pi:9a1f0c2e1b7d4a6f8c3e5b2d9a7f4c1e0b8d6a35",
                // A registry port is not a tag; a reference carrying both must still read the tag.
                "localhost:5000/agent-pi:0.73.2",
                // The series rule reaches only version-shaped tags that stop short of a patch, so a
                // pre-release, a four-part build number and a hand-built dev tag all still name a build.
                "ghcr.io/hephaestus-build/agent-pi:0.74.0-rc.1",
                "ghcr.io/hephaestus-build/agent-pi:1.2.3.4",
                "ghcr.io/hephaestus-build/agent-pi:dev",
            })
    void shouldAcceptAReferenceThatCanNameAMatchedBuild(String reference) {
        assertThatCode(() -> guardFor(reference)).doesNotThrowAnyException();
    }

    @Test
    void shouldAcceptADigestPin() {
        assertThatCode(() -> guardFor("ghcr.io/hephaestus-build/agent-pi@sha256:" + "a".repeat(64)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ghcr.io/hephaestus-build/agent-pi:latest",
                "ghcr.io/hephaestus-build/agent-pi:stable",
                "ghcr.io/hephaestus-build/agent-pi:edge",
                "ghcr.io/hephaestus-build/agent-pi:main",
            })
    void shouldRefuseAReleaseChannelBecauseItNamesAnotherReleasesImage(String reference) {
        assertThatThrownBy(() -> guardFor(reference))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(reference)
                .hasMessageContaining("different commit")
                .hasMessageContaining("docs/admin/release-image-lock.md");
    }

    /**
     * The release workflow retags {@code agent-pi:<major>.<minor>} on every release in the line, so
     * a partial version is a channel that happens to be spelled in digits — and unlike the named
     * channels it is a tag this repository actually publishes.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "ghcr.io/hephaestus-build/agent-pi:0.73",
                "ghcr.io/hephaestus-build/agent-pi:0",
                "ghcr.io/hephaestus-build/agent-pi:v1.2",
                "localhost:5000/agent-pi:0.73",
            })
    void shouldRefuseAVersionSeriesBecauseItMovesOnTheNextPatchRelease(String reference) {
        assertThatThrownBy(() -> guardFor(reference))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(reference)
                .hasMessageContaining("version series")
                .hasMessageContaining("different commit")
                .hasMessageContaining("docs/admin/release-image-lock.md");
    }

    /** Docker resolves an untagged reference to the release channel, so this is the same defect. */
    @ParameterizedTest
    @ValueSource(strings = {"ghcr.io/hephaestus-build/agent-pi", "localhost:5000/agent-pi"})
    void shouldRefuseAReferenceThatNamesNoTag(String reference) {
        assertThatThrownBy(() -> guardFor(reference))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(reference)
                .hasMessageContaining("names no tag")
                .hasMessageContaining("different commit");
    }

    /**
     * What an empty {@code APP_VERSION} produces: a substrate that interpolates a missing image tag
     * to the empty string leaves the derivation with nothing to append.
     */
    @ParameterizedTest
    @ValueSource(strings = {"ghcr.io/hephaestus-build/agent-pi:", "ghcr.io/hephaestus-build/agent-pi:-bad"})
    void shouldRefuseAReferenceWhoseTagIsNotUsable(String reference) {
        assertThatThrownBy(() -> guardFor(reference))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(reference)
                .hasMessageContaining("APP_VERSION")
                .hasMessageContaining("docs/admin/release-image-lock.md");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ghcr.io/hephaestus-build/agent-pi@sha256:abc123",
                "ghcr.io/hephaestus-build/agent-pi@sha256:",
            })
    void shouldRefuseAMalformedDigest(String reference) {
        assertThatThrownBy(() -> guardFor(reference))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(reference)
                .hasMessageContaining("64 lowercase hex");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void shouldRefuseAnUnresolvedReference(String reference) {
        assertThatThrownBy(() -> guardFor(reference))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hephaestus.agent.image.reference");
    }

    @Test
    void shouldAllowStartupWhenTheTagCameFromAnUnsetAppVersion() {
        assertThatCode(() ->
                        guardFor("ghcr.io/hephaestus-build/agent-pi:" + AgentImageReferenceGuard.DEVELOPMENT_VERSION))
                .doesNotThrowAnyException();
    }

    /**
     * The guard only guards what Spring instantiates, and nothing else references the class — so
     * dropping its stereotype disables every assertion above without failing any of them.
     */
    @Test
    void shouldBeRegisteredByComponentScan() {
        // Spring's own scanner with its default stereotype filters and nothing else, so the class is
        // a candidate for exactly the reason the running application makes it one.
        var scanner = new ClassPathScanningCandidateComponentProvider(true);

        assertThat(scanner.findCandidateComponents(AgentImageReferenceGuard.class.getPackageName()))
                .as("AgentImageReferenceGuard is a component scan candidate")
                .extracting(BeanDefinition::getBeanClassName)
                .contains(AgentImageReferenceGuard.class.getName());
    }
}
