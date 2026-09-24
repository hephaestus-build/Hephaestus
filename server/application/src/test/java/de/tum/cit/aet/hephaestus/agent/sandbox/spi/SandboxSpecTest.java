package de.tum.cit.aet.hephaestus.agent.sandbox.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SandboxSpecTest extends BaseUnitTest {

    @Test
    void shouldRejectBlankImage() {
        assertThatThrownBy(() -> new SandboxSpec(
                        UUID.randomUUID(),
                        "  ",
                        List.of(),
                        Map.of(),
                        null,
                        ResourceLimits.DEFAULT,
                        SecurityProfile.DEFAULT,
                        Map.of(),
                        "/workspace/out"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void shouldAcceptValidSpec() {
        // networkPolicy, securityProfile can be null
        // command, environment, inputFiles are defaulted to empty collections
        var spec = new SandboxSpec(
                UUID.randomUUID(),
                "alpine:latest",
                null,
                null,
                null,
                ResourceLimits.DEFAULT,
                null,
                null,
                "/workspace/out");
        assertThat(spec.jobId()).isNotNull();
        assertThat(spec.command()).isEmpty();
        assertThat(spec.environment()).isEmpty();
        assertThat(spec.inputFiles()).isEmpty();
    }

    @Nested
    class ResourceLimitsValidation {

        @Test
        void shouldRejectZeroMemory() {
            assertThatThrownBy(() -> new ResourceLimits(0, 2.0, 256, Duration.ofMinutes(10)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("memoryBytes");
        }

        @Test
        void shouldRejectNegativeCpus() {
            assertThatThrownBy(() -> new ResourceLimits(4L * 1024 * 1024 * 1024, -1.0, 256, Duration.ofMinutes(10)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cpus");
        }

        @Test
        void shouldRejectZeroPids() {
            assertThatThrownBy(() -> new ResourceLimits(4L * 1024 * 1024 * 1024, 2.0, 0, Duration.ofMinutes(10)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pidsLimit");
        }

        @Test
        void shouldRejectNullMaxRuntime() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ResourceLimits(4L * 1024 * 1024 * 1024, 2.0, 256, null))
                    .withMessageContaining("maxRuntime");
        }

        @Test
        void shouldRejectZeroMaxRuntime() {
            assertThatThrownBy(() -> new ResourceLimits(4L * 1024 * 1024 * 1024, 2.0, 256, Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxRuntime");
        }

        @Test
        void shouldAcceptDefaults() {
            assertThat(ResourceLimits.DEFAULT.memoryBytes()).isEqualTo(4L * 1024 * 1024 * 1024);
            assertThat(ResourceLimits.DEFAULT.cpus()).isEqualTo(2.0);
            assertThat(ResourceLimits.DEFAULT.pidsLimit()).isEqualTo(512);
            assertThat(ResourceLimits.DEFAULT.maxRuntime()).isEqualTo(Duration.ofMinutes(10));
        }

        @Test
        void shouldRejectExcessiveMemory() {
            assertThatThrownBy(() ->
                            new ResourceLimits(ResourceLimits.MAX_MEMORY_BYTES + 1, 2.0, 256, Duration.ofMinutes(10)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds maximum");
        }

        @Test
        void shouldRejectExcessiveCpus() {
            assertThatThrownBy(() -> new ResourceLimits(
                            4L * 1024 * 1024 * 1024, ResourceLimits.MAX_CPUS + 0.1, 256, Duration.ofMinutes(10)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds maximum");
        }

        @Test
        void shouldRejectExcessivePids() {
            assertThatThrownBy(() -> new ResourceLimits(
                            4L * 1024 * 1024 * 1024, 2.0, ResourceLimits.MAX_PIDS + 1, Duration.ofMinutes(10)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds maximum");
        }

        @Test
        void shouldRejectExcessiveRuntime() {
            assertThatThrownBy(() -> new ResourceLimits(
                            4L * 1024 * 1024 * 1024, 2.0, 256, ResourceLimits.MAX_RUNTIME.plusSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds maximum");
        }

        @Test
        void shouldAcceptAtMaximumBounds() {
            var limits = new ResourceLimits(
                    ResourceLimits.MAX_MEMORY_BYTES,
                    ResourceLimits.MAX_CPUS,
                    ResourceLimits.MAX_PIDS,
                    ResourceLimits.MAX_RUNTIME);
            assertThat(limits.memoryBytes()).isEqualTo(ResourceLimits.MAX_MEMORY_BYTES);
        }
    }
}
