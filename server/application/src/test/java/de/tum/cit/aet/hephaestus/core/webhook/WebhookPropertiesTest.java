package de.tum.cit.aet.hephaestus.core.webhook;

import static de.tum.cit.aet.hephaestus.core.webhook.WebhookPropertiesFixture.gibibytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/**
 * The bounds a receiver can be configured into that make it reject what it already accepted.
 * {@code @Validated} is what runs the cross-record assertion at bind time, so these go through a real
 * validator rather than through the constructor.
 */
class WebhookPropertiesTest extends BaseUnitTest {

    private static final long MAX_PAYLOAD = 26_214_400L;

    @Test
    void refusesAStreamTooSmallToHoldWhatTheReceiverAccepts() {
        assertThat(violations(streamOf(DataSize.ofBytes(3 * MAX_PAYLOAD), Map.of())))
                .as("below four maximum payloads the stream rejects deliveries the filter already admitted")
                .singleElement()
                .satisfies(message -> assertThat(message).contains("max-bytes"));
    }

    @Test
    void refusesAPerStreamOverrideTooSmallToHoldWhatTheReceiverAccepts() {
        assertThat(violations(streamOf(gibibytes(1), Map.of("github", DataSize.ofBytes(MAX_PAYLOAD)))))
                .as("an override is the value the stream actually runs with, so it is bound by the same floor")
                .hasSize(1);
    }

    @Test
    void acceptsAStreamExactlyAtTheFloor() {
        assertThat(violations(streamOf(DataSize.ofBytes(4 * MAX_PAYLOAD), Map.of("github", gibibytes(8)))))
                .isEmpty();
    }

    @Test
    void acceptsTheShippedShape() {
        assertThat(violations(WebhookPropertiesFixture.properties())).isEmpty();
    }

    @Test
    void refusesADedupWindowShorterThanAVendorsReplayTolerance() {
        assertThatThrownBy(() -> stream(Duration.ofMinutes(2), gibibytes(1), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max vendor replay tolerance");
    }

    @Test
    void refusesAStreamBoundOfZeroBytes() {
        assertThatThrownBy(() -> stream(Duration.ofMinutes(10), DataSize.ofBytes(0), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stream.maxBytes must be at least 1 byte");
    }

    private static WebhookProperties streamOf(DataSize maxBytes, Map<String, DataSize> byStream) {
        return WebhookPropertiesFixture.with(stream(Duration.ofMinutes(10), maxBytes, byStream));
    }

    private static WebhookProperties.Stream stream(
            Duration duplicateWindow, DataSize maxBytes, Map<String, DataSize> byStream) {
        return new WebhookProperties.Stream(
                duplicateWindow,
                Duration.ofDays(180),
                Map.of(),
                maxBytes,
                byStream,
                gibibytes(64),
                false,
                Duration.ofMinutes(5),
                Duration.ofSeconds(60));
    }

    private static List<String> violations(WebhookProperties properties) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(properties).stream()
                    .map(v -> v.getMessage())
                    .toList();
        }
    }
}
