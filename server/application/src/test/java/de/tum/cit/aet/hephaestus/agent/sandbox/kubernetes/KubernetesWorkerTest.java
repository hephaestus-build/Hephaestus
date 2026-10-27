package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ExecSandboxes;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The worker says when its sandbox runtime may start sandboxes, so the turn sandbox pool fills at once. */
class KubernetesWorkerTest extends BaseUnitTest {

    private static final KubernetesIsolationProbe.Proof PASSED =
            new KubernetesIsolationProbe.Proof(Instant.EPOCH, List.of(), true, "", 256);
    private static final KubernetesIsolationProbe.Proof FAILED =
            new KubernetesIsolationProbe.Proof(Instant.EPOCH, List.of("egress"), true, "", 256);

    private final List<Object> published = new ArrayList<>();
    private final KubernetesWorker worker =
            new KubernetesWorker(KubernetesFixtures.properties("sandboxes"), "worker-1", 8081, published::add);

    @Test
    void shouldAnnounceTheRuntimeReadyWhenAnIsolationProofPassesAfterNoneOrAFailedOne() {
        worker.proved(FAILED);
        assertThat(published).isEmpty();

        worker.proved(PASSED);
        worker.proved(PASSED);
        assertThat(published).containsExactly(new ExecSandboxes.SandboxRuntimeReady());

        worker.proved(FAILED);
        worker.proved(PASSED);
        assertThat(published).hasSize(2);
    }
}
