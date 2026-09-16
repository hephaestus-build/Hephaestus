package de.tum.cit.aet.hephaestus.core.runtime.worker.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class FrameCodecRoundTripTest extends BaseUnitTest {

    private final FrameCodec codec = new FrameCodec(new ObjectMapper());

    @Test
    void preservesAllFieldsThroughRoundTrip() {
        // CapacityReport carries six ints; if the sealed-switch + polymorphic deser is wired wrong
        // the decoded record will not equal the input. Stronger signal than instanceof-check.
        CapacityReport payload = new CapacityReport(4, 2, 1, 0, 3, 2);
        FrameEnvelope envelope = FrameEnvelope.of(payload);

        FrameEnvelope decoded = codec.decode(codec.encode(envelope));

        assertThat(decoded.version()).isEqualTo(FrameEnvelope.CURRENT_VERSION);
        assertThat(decoded.frameId()).isEqualTo(envelope.frameId());
        assertThat(decoded.payload()).isEqualTo(payload);
    }

    @Test
    void rejectsOversizedFrameOnDecode() {
        String tooLarge = "x".repeat(FrameCodec.MAX_FRAME_BYTES + 1);
        assertThatThrownBy(() -> codec.decode(tooLarge))
                .isInstanceOf(FrameCodec.FrameCodecException.class)
                .hasMessageContaining("exceeds " + FrameCodec.MAX_FRAME_BYTES);
    }
}
