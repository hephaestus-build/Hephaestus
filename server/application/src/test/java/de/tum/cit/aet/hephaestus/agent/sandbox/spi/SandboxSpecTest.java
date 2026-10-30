package de.tum.cit.aet.hephaestus.agent.sandbox.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxTestLimits;
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
        assertThatThrownBy(() -> spec("  ", "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void shouldRequireAGatewayCredential() {
        assertThatThrownBy(() -> spec("alpine:latest", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Gateway credential required");
    }

    @Test
    void shouldAcceptValidSpec() {
        var spec = spec("alpine:latest", "token");
        assertThat(spec.jobId()).isNotNull();
        assertThat(spec.command()).isEmpty();
        assertThat(spec.environment()).isEmpty();
        assertThat(spec.inputFiles()).isEmpty();
        assertThat(spec.workDeadline()).isNull();
    }

    private static SandboxSpec spec(String image, String token) {
        return new SandboxSpec(
                UUID.randomUUID(),
                null,
                image,
                List.of(),
                Map.of(),
                token,
                SandboxTestLimits.ATTEMPT,
                null,
                Map.of(),
                Map.of(),
                List.of());
    }

    @Nested
    class ResourceLimitsValidation {

        @Test
        void shouldRejectNegativeCpus() {
            assertThatThrownBy(() -> new ResourceLimits(-1.0, Duration.ofMinutes(10)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cpus");
        }

        @Test
        void shouldRejectNullMaxRuntime() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ResourceLimits(2.0, null))
                    .withMessageContaining("maxRuntime");
        }

        @Test
        void shouldRejectZeroMaxRuntime() {
            assertThatThrownBy(() -> new ResourceLimits(2.0, Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxRuntime");
        }

        @Test
        void shouldAcceptDefaults() {
            assertThat(SandboxTestLimits.ATTEMPT.cpus()).isEqualTo(2.0);
            assertThat(SandboxTestLimits.ATTEMPT.maxRuntime()).isEqualTo(Duration.ofMinutes(10));
        }

        @Test
        void shouldRejectExcessiveCpus() {
            assertThatThrownBy(() -> new ResourceLimits(ResourceLimits.MAX_CPUS + 0.1, Duration.ofMinutes(10)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds maximum");
        }

        @Test
        void shouldRejectExcessiveRuntime() {
            assertThatThrownBy(() -> new ResourceLimits(2.0, ResourceLimits.MAX_RUNTIME.plusSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds maximum");
        }

        @Test
        void shouldAcceptAtMaximumBounds() {
            var limits = new ResourceLimits(ResourceLimits.MAX_CPUS, ResourceLimits.MAX_RUNTIME);
            assertThat(limits.cpus()).isEqualTo(ResourceLimits.MAX_CPUS);
        }
    }
}
