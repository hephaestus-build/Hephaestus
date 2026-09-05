package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.NetworkPolicy;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SecurityProfile;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ContainerSecurityPolicyTest extends BaseUnitTest {

    private ContainerSecurityPolicy securityPolicy;

    @BeforeEach
    void setUp() {
        DockerSandboxProperties properties =
                new DockerSandboxProperties("unix:///var/run/docker.sock", false, null, null, null, "docker");
        securityPolicy = new ContainerSecurityPolicy(properties, null);
    }

    @Nested
    class SecurityFlags {

        @Test
        void shouldApplyDefaultSecurityOptions() {
            DockerOperations.HostConfigSpec config = securityPolicy.buildHostConfig(
                    SecurityProfile.DEFAULT, ResourceLimits.DEFAULT, new NetworkPolicy(false, null, null));

            assertThat(config.readonlyRootfs()).isFalse();
            assertThat(config.privileged()).isFalse();
            assertThat(config.capDrop()).containsExactly("ALL");
            assertThat(config.securityOpts()).contains("no-new-privileges");
            assertThat(config.cgroupnsMode()).isEqualTo("private");
            assertThat(config.ipcMode()).isEqualTo("none");
        }

        @Test
        void shouldSetMemoryNoSwap() {
            DockerOperations.HostConfigSpec config = securityPolicy.buildHostConfig(
                    SecurityProfile.DEFAULT, ResourceLimits.DEFAULT, new NetworkPolicy(false, null, null));

            assertThat(config.memoryBytes()).isEqualTo(4L * 1024 * 1024 * 1024);
            assertThat(config.memorySwapBytes()).isEqualTo(config.memoryBytes());
        }

        @Test
        void shouldSetCpuLimit() {
            DockerOperations.HostConfigSpec config = securityPolicy.buildHostConfig(
                    SecurityProfile.DEFAULT, ResourceLimits.DEFAULT, new NetworkPolicy(false, null, null));

            assertThat(config.nanoCpus()).isEqualTo(2_000_000_000L);
        }

        @Test
        void shouldSetPidLimit() {
            DockerOperations.HostConfigSpec config = securityPolicy.buildHostConfig(
                    SecurityProfile.DEFAULT, ResourceLimits.DEFAULT, new NetworkPolicy(false, null, null));

            assertThat(config.pidsLimit()).isEqualTo(512);
        }

        @Test
        void shouldSetUlimitNofile() {
            DockerOperations.HostConfigSpec config = securityPolicy.buildHostConfig(
                    SecurityProfile.DEFAULT, ResourceLimits.DEFAULT, new NetworkPolicy(false, null, null));

            assertThat(config.ulimits()).containsKey("nofile");
            var nofile = config.ulimits().get("nofile");
            assertThat(nofile).isNotNull();
            assertThat(nofile.soft()).isEqualTo(1024);
            assertThat(nofile.hard()).isEqualTo(1024);
        }
    }

    @Nested
    class NetworkDns {

        @Test
        void shouldBlockDnsWhenNoInternet() {
            DockerOperations.HostConfigSpec config = securityPolicy.buildHostConfig(
                    SecurityProfile.DEFAULT, ResourceLimits.DEFAULT, new NetworkPolicy(false, null, null));

            assertThat(config.dns()).containsExactly("0.0.0.0");
        }

        @Test
        void shouldAllowDnsWithInternet() {
            DockerOperations.HostConfigSpec config = securityPolicy.buildHostConfig(
                    SecurityProfile.DEFAULT, ResourceLimits.DEFAULT, new NetworkPolicy(true, null, null));

            assertThat(config.dns()).isEmpty();
        }

        @Test
        void shouldBlockDnsWhenNetworkPolicyNull() {
            DockerOperations.HostConfigSpec config =
                    securityPolicy.buildHostConfig(SecurityProfile.DEFAULT, ResourceLimits.DEFAULT, null);

            assertThat(config.dns()).containsExactly("0.0.0.0");
        }
    }

    @Nested
    class TmpfsMounts {

        @Test
        void shouldConfigureDefaultTmpfs() {
            DockerOperations.HostConfigSpec config = securityPolicy.buildHostConfig(
                    SecurityProfile.DEFAULT, ResourceLimits.DEFAULT, new NetworkPolicy(false, null, null));

            assertThat(config.tmpfsMounts()).containsKey("/tmp");
            assertThat(config.tmpfsMounts()).containsKey("/run");
            assertThat(config.tmpfsMounts()).containsKey("/home/agent/.local");
            assertThat(config.tmpfsMounts().get("/tmp")).contains("noexec");
        }
    }

    @Nested
    class RuntimeConfiguration {

        @Test
        void shouldUseGvisorFromProfile() {
            SecurityProfile gvisorProfile = new SecurityProfile("runsc", "none", List.of("ALL"), Map.of());

            DockerOperations.HostConfigSpec config = securityPolicy.buildHostConfig(
                    gvisorProfile, ResourceLimits.DEFAULT, new NetworkPolicy(false, null, null));

            assertThat(config.runtime()).isEqualTo("runsc");
        }

        @Test
        void shouldUseGlobalRuntime() {
            DockerSandboxProperties propsWithRuntime =
                    new DockerSandboxProperties("unix:///var/run/docker.sock", false, null, "runsc", null, "docker");
            ContainerSecurityPolicy policyWithRuntime = new ContainerSecurityPolicy(propsWithRuntime, null);

            DockerOperations.HostConfigSpec config = policyWithRuntime.buildHostConfig(
                    SecurityProfile.DEFAULT, ResourceLimits.DEFAULT, new NetworkPolicy(false, null, null));

            assertThat(config.runtime()).isEqualTo("runsc");
        }

        @Test
        void shouldHaveNoRuntimeByDefault() {
            DockerOperations.HostConfigSpec config = securityPolicy.buildHostConfig(
                    SecurityProfile.DEFAULT, ResourceLimits.DEFAULT, new NetworkPolicy(false, null, null));

            assertThat(config.runtime()).isNull();
        }
    }

    @Nested
    class Labels {

        @Test
        void shouldBuildLabels() {
            UUID jobId = UUID.randomUUID();
            Map<String, String> labels = securityPolicy.buildLabels(jobId);

            assertThat(labels).containsEntry("hephaestus.managed", "true");
            assertThat(labels).containsEntry("hephaestus.job-id", jobId.toString());
        }
    }

    @Nested
    class SeccompProfile {

        @Test
        void shouldIncludeSeccompWhenProvided() {
            ContainerSecurityPolicy policyWithSeccomp = new ContainerSecurityPolicy(
                    new DockerSandboxProperties("unix:///var/run/docker.sock", false, null, null, null, "docker"),
                    "{\"defaultAction\":\"SCMP_ACT_ERRNO\"}");

            DockerOperations.HostConfigSpec config = policyWithSeccomp.buildHostConfig(
                    SecurityProfile.DEFAULT, ResourceLimits.DEFAULT, new NetworkPolicy(false, null, null));

            assertThat(config.securityOpts())
                    .anyMatch(opt -> opt.startsWith("seccomp=") && opt.contains("SCMP_ACT_ERRNO"));
        }

        @Test
        void shouldNotIncludeSeccompWhenNull() {
            DockerOperations.HostConfigSpec config = securityPolicy.buildHostConfig(
                    SecurityProfile.DEFAULT, ResourceLimits.DEFAULT, new NetworkPolicy(false, null, null));

            assertThat(config.securityOpts()).noneMatch(opt -> opt.startsWith("seccomp="));
        }
    }

    @Nested
    class CustomResourceLimits {

        @Test
        void shouldApplyCustomLimits() {
            ResourceLimits custom = new ResourceLimits(
                    8L * 1024 * 1024 * 1024, // 8GB
                    4.0,
                    512,
                    Duration.ofMinutes(30));

            DockerOperations.HostConfigSpec config = securityPolicy.buildHostConfig(
                    SecurityProfile.DEFAULT, custom, new NetworkPolicy(false, null, null));

            assertThat(config.memoryBytes()).isEqualTo(8L * 1024 * 1024 * 1024);
            assertThat(config.nanoCpus()).isEqualTo(4_000_000_000L);
            assertThat(config.pidsLimit()).isEqualTo(512);
        }
    }

    @Nested
    class EnforcementFloors {

        @Test
        void shouldEnforceNoNewPrivileges() {
            SecurityProfile laxProfile = new SecurityProfile(null, "none", List.of(), Map.of());

            DockerOperations.HostConfigSpec config = securityPolicy.buildHostConfig(
                    laxProfile, ResourceLimits.DEFAULT, new NetworkPolicy(false, null, null));

            assertThat(config.securityOpts()).contains("no-new-privileges");
        }

        @Test
        void shouldEnforceCapDropAll() {
            SecurityProfile laxProfile = new SecurityProfile(null, "none", List.of(), Map.of());

            DockerOperations.HostConfigSpec config = securityPolicy.buildHostConfig(
                    laxProfile, ResourceLimits.DEFAULT, new NetworkPolicy(false, null, null));

            assertThat(config.capDrop()).containsExactly("ALL");
        }

        @Test
        void shouldEnforceCapDropAllOverPartialList() {
            SecurityProfile partialCapProfile =
                    new SecurityProfile(null, "none", List.of("NET_RAW", "SYS_ADMIN"), Map.of());

            DockerOperations.HostConfigSpec config = securityPolicy.buildHostConfig(
                    partialCapProfile, ResourceLimits.DEFAULT, new NetworkPolicy(false, null, null));

            assertThat(config.capDrop()).containsExactly("ALL");
        }

        @Test
        void shouldEnforcePrivateCgroupns() {
            SecurityProfile laxProfile = new SecurityProfile(null, "none", List.of("ALL"), Map.of());

            DockerOperations.HostConfigSpec config = securityPolicy.buildHostConfig(
                    laxProfile, ResourceLimits.DEFAULT, new NetworkPolicy(false, null, null));

            assertThat(config.cgroupnsMode()).isEqualTo("private");
        }

        @Test
        void shouldRejectHostIpcMode() {
            SecurityProfile hostIpcProfile = new SecurityProfile(null, "host", List.of("ALL"), Map.of());

            DockerOperations.HostConfigSpec config = securityPolicy.buildHostConfig(
                    hostIpcProfile, ResourceLimits.DEFAULT, new NetworkPolicy(false, null, null));

            assertThat(config.ipcMode()).isEqualTo("none");
        }

        @Test
        void shouldRejectShareableIpcMode() {
            SecurityProfile shareableIpcProfile = new SecurityProfile(null, "shareable", List.of("ALL"), Map.of());

            DockerOperations.HostConfigSpec config = securityPolicy.buildHostConfig(
                    shareableIpcProfile, ResourceLimits.DEFAULT, new NetworkPolicy(false, null, null));

            assertThat(config.ipcMode()).isEqualTo("none");
        }

        @Test
        void shouldDefaultIpcToNone() {
            SecurityProfile nullIpcProfile = new SecurityProfile(null, null, List.of("ALL"), Map.of());

            DockerOperations.HostConfigSpec config = securityPolicy.buildHostConfig(
                    nullIpcProfile, ResourceLimits.DEFAULT, new NetworkPolicy(false, null, null));

            assertThat(config.ipcMode()).isEqualTo("none");
        }

        @Test
        void shouldAllowPrivateIpcMode() {
            SecurityProfile privateIpcProfile = new SecurityProfile(null, "private", List.of("ALL"), Map.of());

            DockerOperations.HostConfigSpec config = securityPolicy.buildHostConfig(
                    privateIpcProfile, ResourceLimits.DEFAULT, new NetworkPolicy(false, null, null));

            assertThat(config.ipcMode()).isEqualTo("private");
        }

        @Test
        void shouldEnforceMandatoryTmpfs() {
            SecurityProfile weakTmpfsProfile = new SecurityProfile(
                    null, "none", List.of("ALL"), Map.of("/tmp", "rw,exec,size=100g", "/custom", "rw,size=512m"));

            DockerOperations.HostConfigSpec config = securityPolicy.buildHostConfig(
                    weakTmpfsProfile, ResourceLimits.DEFAULT, new NetworkPolicy(false, null, null));

            // Enforces noexec on /tmp even though the caller's profile requested "exec"
            assertThat(config.tmpfsMounts().get("/tmp")).contains("noexec");
            assertThat(config.tmpfsMounts()).containsKey("/custom");
            assertThat(config.tmpfsMounts()).containsKey("/run");
            assertThat(config.tmpfsMounts()).containsKey("/home/agent/.local");
        }

        @Test
        void shouldHandleNullTmpfsMounts() {
            SecurityProfile nullTmpfsProfile = new SecurityProfile(null, "none", List.of("ALL"), null);

            DockerOperations.HostConfigSpec config = securityPolicy.buildHostConfig(
                    nullTmpfsProfile, ResourceLimits.DEFAULT, new NetworkPolicy(false, null, null));

            assertThat(config.tmpfsMounts()).containsKey("/tmp");
            assertThat(config.tmpfsMounts()).containsKey("/run");
            assertThat(config.tmpfsMounts()).containsKey("/home/agent/.local");
            assertThat(config.tmpfsMounts().get("/tmp")).contains("noexec");
        }

        @Test
        void shouldPreventRuntimeDowngrade() {
            DockerSandboxProperties propsWithRuntime =
                    new DockerSandboxProperties("unix:///var/run/docker.sock", false, null, "runsc", null, "docker");
            ContainerSecurityPolicy policyWithRuntime = new ContainerSecurityPolicy(propsWithRuntime, null);

            SecurityProfile runcProfile = new SecurityProfile("runc", "none", List.of("ALL"), Map.of());

            DockerOperations.HostConfigSpec config = policyWithRuntime.buildHostConfig(
                    runcProfile, ResourceLimits.DEFAULT, new NetworkPolicy(false, null, null));

            assertThat(config.runtime()).isEqualTo("runsc");
        }
    }

    @Nested
    @DisplayName("Ulimits")
    class UlimitTests {

        @Test
        void shouldSetNprocUlimit() {
            DockerOperations.HostConfigSpec config = securityPolicy.buildHostConfig(
                    SecurityProfile.DEFAULT, ResourceLimits.DEFAULT, new NetworkPolicy(false, null, null));

            assertThat(config.ulimits()).doesNotContainKey("nproc");
        }

        @Test
        void shouldDisableCoreDumps() {
            DockerOperations.HostConfigSpec config = securityPolicy.buildHostConfig(
                    SecurityProfile.DEFAULT, ResourceLimits.DEFAULT, new NetworkPolicy(false, null, null));

            assertThat(config.ulimits()).containsKey("core");
            var core = config.ulimits().get("core");
            assertThat(core).isNotNull();
            assertThat(core.soft()).isZero();
            assertThat(core.hard()).isZero();
        }
    }
}
