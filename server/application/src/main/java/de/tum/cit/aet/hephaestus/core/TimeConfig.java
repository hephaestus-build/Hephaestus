package de.tum.cit.aet.hephaestus.core;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.NoneNestedConditions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * The application {@link Clock}. Deliberately not gated on the runtime role: beans that every context needs inject it.
 */
@Configuration
public class TimeConfig {

    @Bean("clock")
    @Conditional(SystemClockCondition.class)
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean("clock")
    @ConditionalOnEvaluation
    public ControlledClock controlledClock() {
        return new ControlledClock(Clock.systemUTC().instant());
    }

    static final class SystemClockCondition extends NoneNestedConditions {

        SystemClockCondition() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnEvaluation
        static final class EvaluationEnabled {}
    }
}
