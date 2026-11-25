package de.tum.cit.aet.hephaestus.core.privacy.spi;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.simple.JdbcClient;

@Tag("unit")
class OwnerSweepsTest {

    @ParameterizedTest
    @MethodSource("admittedRules")
    void shouldRefuseARuleThatNeedsLifecycleAdmissionWithoutTouchingTheDatabase(RowSweepRule rule) {
        var jdbc = mock(JdbcClient.class);

        assertThatThrownBy(() -> new OwnerSweeps(jdbc).expire(rule, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(rule.name());
        verifyNoInteractions(jdbc);
    }

    static Stream<RowSweepRule> admittedRules() {
        return Arrays.stream(RowSweepRule.values()).filter(RowSweepRule::requiresLifecycleAdmission);
    }
}
