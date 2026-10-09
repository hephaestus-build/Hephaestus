package de.tum.cit.aet.hephaestus.core.runtime.worker.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class FrameCodecRoundTripTest extends BaseUnitTest {

    private final FrameCodec codec = new FrameCodec(new ObjectMapper());

    @Test
    void preservesAllFieldsThroughRoundTrip() {
        // If the sealed-switch + polymorphic deser is wired wrong the decoded record will not equal
        // the input. Stronger signal than instanceof-check.
        CapacityReport payload = new CapacityReport(4, 1, 3);
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

    @Test
    void shouldRoundTripUnicodeTextWhenAFrameIsEncodedAndDecoded() {
        var input = FrameEnvelope.of(new CancelJob(UUID.randomUUID().toString(), "Heph 🌍"));
        assertThat(codec.decode(codec.encode(input))).isEqualTo(input);
    }

    @Test
    void checksUtf8BytesRatherThanJavaCharacterCount() {
        String oversized = "🌍".repeat(FrameCodec.MAX_FRAME_BYTES / 4 + 1);
        assertThatThrownBy(() -> codec.decode(oversized)).isInstanceOf(FrameCodec.FrameCodecException.class);
        assertThatThrownBy(() -> codec.encode(
                        FrameEnvelope.of(new CancelJob(UUID.randomUUID().toString(), oversized))))
                .isInstanceOf(FrameCodec.FrameCodecException.class);
    }
}
