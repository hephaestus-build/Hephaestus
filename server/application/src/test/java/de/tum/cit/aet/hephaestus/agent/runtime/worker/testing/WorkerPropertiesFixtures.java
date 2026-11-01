package de.tum.cit.aet.hephaestus.agent.runtime.worker.testing;

import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerProperties;
import java.time.Duration;

public final class WorkerPropertiesFixtures {

    private WorkerPropertiesFixtures() {}

    /** Minimal {@link WorkerProperties} for unit-test wiring with the given capacity. */
    public static WorkerProperties minimal(String reviewMax) {
        return new WorkerProperties(
                "test-worker",
                new WorkerProperties.Capacity(reviewMax),
                new WorkerProperties.Drain(Duration.ofMinutes(5)));
    }

    public static WorkerProperties withDrain(Duration drainTimeout) {
        return new WorkerProperties("w", new WorkerProperties.Capacity("2"), new WorkerProperties.Drain(drainTimeout));
    }
}
