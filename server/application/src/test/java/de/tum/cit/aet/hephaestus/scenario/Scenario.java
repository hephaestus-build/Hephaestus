package de.tum.cit.aet.hephaestus.scenario;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** A named timeline of steps, each at an offset from the start. Steps at one offset run in the order given. */
public record Scenario(String name, List<Timed> steps) {

    public Scenario {
        steps = List.copyOf(steps);
    }

    public record Timed(Duration offset, Step step) {}

    /** A scenario that starts from the {@link Fixture}. */
    public static Builder named(String name) {
        Builder builder = new Builder(name);
        Fixture.steps().forEach(step -> builder.at(Duration.ZERO, step));
        return builder;
    }

    public static final class Builder {
        private final String name;
        private final List<Timed> steps = new ArrayList<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder at(Duration offset, Step... steps) {
            for (Step step : steps) {
                this.steps.add(new Timed(offset, step));
            }
            return this;
        }

        public Scenario build() {
            return new Scenario(name, steps);
        }
    }
}
