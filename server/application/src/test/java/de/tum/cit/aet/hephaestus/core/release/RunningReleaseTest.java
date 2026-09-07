package de.tum.cit.aet.hephaestus.core.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class RunningReleaseTest {
    static final String COMMIT = "a".repeat(40);

    static RunningRelease identity(String version, String commit, MockEnvironment environment) {
        var properties = new Properties();
        properties.setProperty("version", version);
        properties.setProperty("commit", commit);
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton("buildProperties", new BuildProperties(properties));
        return new RunningRelease(
                factory.getBeanProvider(BuildProperties.class),
                environment,
                JsonMapper.builder().build());
    }

    static String projection(String version, String commit) {
        return "{\"release\":\"v" + version + "\",\"commit\":\"" + commit
                + "\",\"images\":{\"application-server\":\"ghcr.io/hephaestus-build/application-server@sha256:"
                + "b".repeat(64) + "\"}}";
    }

    @Test
    void shouldReportUnknownWhenNoBuildMetadataExists() {
        var factory = new DefaultListableBeanFactory();
        var running = new RunningRelease(
                        factory.getBeanProvider(BuildProperties.class),
                        new MockEnvironment(),
                        JsonMapper.builder().build())
                .get();
        assertThat(running.version()).isEqualTo("unknown");
        assertThat(running.identityStatus()).isEqualTo("UNKNOWN");
        assertThat(running.images()).isEmpty();
    }

    @Test
    void shouldKeepArtifactVersionDiagnosticWhenBuildCommitIsUnknown() {
        var running = identity("0.77.4", "unknown", new MockEnvironment()).get();
        assertThat(running.version()).isEqualTo("0.77.4");
        assertThat(running.commit()).isEqualTo("unknown");
        assertThat(running.channel()).isEqualTo("unknown");
        assertThat(running.identityStatus()).isEqualTo("UNKNOWN");
    }

    @Test
    void shouldNotTreatArtifactVersionAsReleaseWhenOnlyCommitIsKnown() {
        var running = identity("0.77.4", COMMIT, new MockEnvironment()).get();
        assertThat(running.version()).isEqualTo("0.77.4");
        assertThat(running.commit()).isEqualTo(COMMIT);
        assertThat(running.channel()).isEqualTo("unknown");
    }

    @Test
    void shouldUseDeploymentProjectionWhenBuildCommitAgrees() {
        var running = identity(
                        "0.0.0-development",
                        COMMIT,
                        new MockEnvironment()
                                .withProperty("HEPHAESTUS_DEPLOYMENT_IDENTITY", projection("1.2.3", COMMIT))
                                .withProperty("hephaestus.runtime.worker.enabled", "false")
                                .withProperty("hephaestus.runtime.webhook.enabled", "false"))
                .get();
        assertThat(running.version()).isEqualTo("1.2.3");
        assertThat(running.roles()).containsExactly("server");
        assertThat(running.identityStatus()).isEqualTo("DEPLOYMENT_REPORTED");
        assertThat(running.images()).hasSize(1);
    }

    @Test
    void shouldNotAdoptDeploymentVersionWhenBuildDisagrees() {
        var running = identity(
                        "1.0.0",
                        COMMIT,
                        new MockEnvironment()
                                .withProperty("HEPHAESTUS_DEPLOYMENT_IDENTITY", projection("1.2.3", "c".repeat(40))))
                .get();
        assertThat(running.identityStatus()).isEqualTo("MISMATCH");
        assertThat(running.version()).isEqualTo("1.0.0");
    }

    @Test
    void shouldRejectMalformedProjectionWithoutLeakingItsContent() {
        var running = identity(
                        "1.0.0",
                        COMMIT,
                        new MockEnvironment().withProperty("HEPHAESTUS_DEPLOYMENT_IDENTITY", "secret-tenant-token"))
                .get();
        assertThat(running.identityStatus()).isEqualTo("INVALID");
        assertThat(running.toString()).doesNotContain("secret-tenant-token");
    }
}
