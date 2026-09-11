package de.tum.cit.aet.hephaestus.core.release;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;
import org.springframework.mock.env.MockEnvironment;

@Tag("unit")
class RunningReleaseTest {
    static final String COMMIT = "a".repeat(40);
    static final String IMAGE = "ghcr.io/hephaestus-build/application-server@sha256:" + "b".repeat(64);

    static RunningRelease running(String version, ReleaseProperties properties, MockEnvironment environment) {
        return new RunningRelease(version, properties, environment);
    }

    static RunningRelease running(String version) {
        return running(version, new ReleaseProperties(COMMIT, IMAGE, true), new MockEnvironment());
    }

    @Test
    void shouldReportReleaseChannelWithLockIdentityWhenVersionIsReleaseTag() {
        var identity = running("1.2.3").get();
        assertThat(identity.version()).isEqualTo("1.2.3");
        assertThat(identity.channel()).isEqualTo(ReleaseChannel.RELEASE);
        assertThat(identity.commit()).isEqualTo(COMMIT);
        assertThat(identity.image()).isEqualTo(IMAGE);
    }

    @Test
    void shouldReportCommitChannelWhenDeploymentFollowsDefaultBranch() {
        assertThat(running(COMMIT).get().channel()).isEqualTo(ReleaseChannel.COMMIT);
    }

    @Test
    void shouldReportDevelopmentChannelWhenVersionIsPlaceholderOrUnbounded() {
        for (String version : new String[] {"0.0.0-development", "DEV", "v1.2.3", "1.2.3-rc.1", "01.2.3", "1.0"}) {
            assertThat(running(version).get().channel()).as(version).isEqualTo(ReleaseChannel.DEVELOPMENT);
        }
    }

    @Test
    void shouldOmitIdentityWhenDeploymentSuppliesNone() {
        var identity = running("0.0.0-development", new ReleaseProperties("", "", true), new MockEnvironment())
                .get();
        assertThat(identity.commit()).isNull();
        assertThat(identity.image()).isNull();
    }

    @Test
    void shouldListOnlyEnabledRolesAndContributeIdentityToActuatorInfo() {
        var environment = new MockEnvironment()
                .withProperty("hephaestus.runtime.worker.enabled", "false")
                .withProperty("hephaestus.runtime.webhook.enabled", "false");
        var running = running("1.2.3", new ReleaseProperties(COMMIT, IMAGE, true), environment);
        assertThat(running.get().roles()).containsExactly("server");
        var info = new Info.Builder();
        running.contribute(info);
        assertThat(info.build().getDetails()).containsEntry("release", running.get());
    }
}
