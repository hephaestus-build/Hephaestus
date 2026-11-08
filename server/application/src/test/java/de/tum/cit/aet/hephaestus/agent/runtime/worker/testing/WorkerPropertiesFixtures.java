package de.tum.cit.aet.hephaestus.agent.runtime.worker.testing;

import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerProperties;
import java.time.Duration;

public final class WorkerPropertiesFixtures {

    private WorkerPropertiesFixtures() {}

    public static WorkerProperties withDrain(Duration drainTimeout) {
        return new WorkerProperties("w", new WorkerProperties.Drain(drainTimeout));
    }
}
