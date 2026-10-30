package de.tum.cit.aet.hephaestus.integration.core.itemevent;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import jakarta.validation.Validation;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ItemEventLedgerPropertiesTest extends BaseUnitTest {
    @Test
    void shouldRequirePositiveDurationsAndBatchSize() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new ItemEventLedgerProperties(
                            Duration.ofNanos(1),
                            Duration.ofNanos(1),
                            1,
                            Duration.ofNanos(1),
                            Duration.ofNanos(1),
                            Duration.ofNanos(1))))
                    .isEmpty();
            var violations = validator.validate(new ItemEventLedgerProperties(
                    Duration.ZERO, Duration.ofNanos(-1), 0, Duration.ZERO, Duration.ofNanos(-1), Duration.ZERO));
            assertThat(violations)
                    .extracting(v -> v.getPropertyPath().toString())
                    .containsExactlyInAnyOrder(
                            "pendingRetryAfter",
                            "pendingLapseAfter",
                            "sweepBatchSize",
                            "retainFor",
                            "quietWindow",
                            "quietWindowCap");
        }
    }
}
