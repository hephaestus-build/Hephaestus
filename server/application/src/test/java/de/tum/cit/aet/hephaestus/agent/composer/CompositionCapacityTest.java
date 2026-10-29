package de.tum.cit.aet.hephaestus.agent.composer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class CompositionCapacityTest {
    @Test
    void shouldBoundLoadedCompositionsWithoutLoweringTheTotalAttemptLimit() {
        var capacity = new CompositionCapacity(4);
        var first = capacity.acquire(Instant.now());
        var second = capacity.acquire(Instant.now());
        var third = capacity.acquire(Instant.now());
        var fourth = capacity.acquire(Instant.now());
        assertThatThrownBy(() -> capacity.acquire(Instant.now()))
                .isInstanceOfSatisfying(
                        CompositionHandedOnException.class,
                        failure -> assertThat(failure.stop()).isEqualTo(CompositionStop.CAPACITY));
        first.close();
        first.close();
        var replacement = capacity.acquire(Instant.now());
        assertThatThrownBy(() -> capacity.acquire(Instant.now())).isInstanceOf(CompositionHandedOnException.class);
        second.close();
        third.close();
        fourth.close();
        replacement.close();
    }
}
