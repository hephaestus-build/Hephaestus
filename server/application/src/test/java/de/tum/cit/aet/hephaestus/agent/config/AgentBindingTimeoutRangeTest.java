package de.tum.cit.aet.hephaestus.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ceiling is the load-bearing end: a run must fit in its sandbox's {@link ResourceLimits#MAX_RUNTIME}, so a
 * binding configured past {@link AgentBindingLimits#MAX_TIMEOUT_SECONDS} would outlast its container.
 */
class AgentBindingTimeoutRangeTest extends BaseUnitTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDownValidator() {
        factory.close();
    }

    @Test
    @DisplayName("both ends of the configurable range are accepted")
    void acceptsTheWholeRange() {
        assertThat(violationsFor(AgentBindingLimits.MIN_TIMEOUT_SECONDS)).isEmpty();
        assertThat(violationsFor(AgentBindingLimits.MAX_TIMEOUT_SECONDS)).isEmpty();
        assertThat(Duration.ofSeconds(AgentBindingLimits.MAX_TIMEOUT_SECONDS))
                .isLessThanOrEqualTo(ResourceLimits.MAX_RUNTIME);
    }

    @Test
    @DisplayName("a timeout past either end is refused before any binding is written")
    void refusesOutsideTheRange() {
        assertThat(violationsFor(AgentBindingLimits.MIN_TIMEOUT_SECONDS - 1))
                .as("below the floor a run cannot finish a single model call")
                .isNotEmpty();
        assertThat(violationsFor(AgentBindingLimits.MAX_TIMEOUT_SECONDS + 1))
                .as("above the ceiling a run can outlast its sandbox")
                .isNotEmpty();
    }

    @Test
    @DisplayName("omitting the timeout keeps the binding's current value rather than failing validation")
    void acceptsAnAbsentTimeout() {
        assertThat(violationsFor(null)).isEmpty();
    }

    private static Set<ConstraintViolation<AgentBindingRequestDTO>> violationsFor(@Nullable Integer timeoutSeconds) {
        AgentBindingRequestDTO request = new AgentBindingRequestDTO(1L, null, timeoutSeconds, null, null, null, null);
        return validator.validate(request).stream()
                .filter(v -> v.getPropertyPath().toString().equals("timeoutSeconds"))
                .collect(Collectors.toSet());
    }
}
