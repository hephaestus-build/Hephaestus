package de.tum.cit.aet.hephaestus.productfeedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.productfeedback.metrics.ProductFeedbackMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ProductFeedbackMetersTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ProductFeedbackMeters meters = new ProductFeedbackMeters(registry);

    private double refused() {
        var counter = registry.find(ProductFeedbackMetrics.STEPS)
                .tags("step", "transition", "outcome", "refused")
                .counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void shouldCountARefusedMoveUnderTheTagsTheAlertReads() {
        meters.failed(new FilingLifecycle.IllegalTransition(
                UUID.randomUUID(), FilingState.FILED, FilingLifecycle.Event.DRAFT_ENDED));

        assertThat(refused()).isEqualTo(1);
    }

    @Test
    void shouldLeaveAnOutageOutOfThatCount() {
        meters.failed(new IllegalStateException("the database is gone"));

        assertThat(refused()).isZero();
    }
}
