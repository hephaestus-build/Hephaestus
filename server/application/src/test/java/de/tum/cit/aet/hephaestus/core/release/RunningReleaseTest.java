package de.tum.cit.aet.hephaestus.core.release;

import static de.tum.cit.aet.hephaestus.core.release.ReleaseFixtures.COMMIT;
import static de.tum.cit.aet.hephaestus.core.release.ReleaseFixtures.IMAGE;
import static de.tum.cit.aet.hephaestus.core.release.ReleaseFixtures.running;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;
import org.springframework.mock.env.MockEnvironment;

@Tag("unit")
class RunningReleaseTest {
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
    void shouldOmitIdentityAndListOnlyEnabledRolesAndContributeToActuatorInfo() {
        var environment = new MockEnvironment()
                .withProperty("hephaestus.runtime.worker.enabled", "false")
                .withProperty("hephaestus.runtime.webhook.enabled", "false");
        var running = new RunningRelease("0.0.0-development", new ReleaseProperties("", "", true), environment);
        assertThat(running.get().commit()).isNull();
        assertThat(running.get().image()).isNull();
        assertThat(running.get().roles()).containsExactly(RuntimeRole.SERVER);
        var info = new Info.Builder();
        running.contribute(info);
        assertThat(info.build().getDetails()).containsEntry("release", running.get());
    }
}
