package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Checks that the resolved agent image declares the runtime contract this server stages for.
 *
 * <p>Resolution decides which image the server will use; this decides whether that image can
 * actually run the server's runners. The two are independent: a hand-pinned digest, a rebuilt
 * branch tag and a stale daemon cache all bypass resolution entirely. Reading the image's labels
 * costs one local inspect and no container, so the answer is available before any work is committed
 * to a sandbox.
 *
 * <p>Reports rather than refuses. Once resolution follows the deployment's own image tag an
 * unmatched image is off the supported paths, so the value here is naming the drift precisely and
 * early — and refusing at startup would take down instances whose sandbox is never used. See
 * ADR 0031.
 */
@Component
@ConditionalOnWorkerRole
public class AgentImageContractVerifier {

    private static final Logger log = LoggerFactory.getLogger(AgentImageContractVerifier.class);

    /** Informational labels quoted back in a drift report, because they name what actually differs. */
    private static final String NODE_VERSION_LABEL = "hephaestus.agent.node-version";

    private static final String PI_VERSION_LABEL = "hephaestus.agent.pi-version";

    public enum Outcome {
        /** The image declares the contract this server stages for. */
        VERIFIED,
        /** The image declares a different contract — server and image cannot agree. */
        MISMATCH,
        /** The image carries no contract label, so it was built before the contract existed. */
        UNLABELLED,
        /** The image could not be inspected; nothing is proven either way. */
        UNKNOWN,
    }

    private final DockerImageOperations imageOps;
    private final MeterRegistry meterRegistry;

    AgentImageContractVerifier(DockerImageOperations imageOps, MeterRegistry meterRegistry) {
        this.imageOps = imageOps;
        this.meterRegistry = meterRegistry;
    }

    public Outcome verify(String image) {
        Outcome outcome = evaluate(image);
        meterRegistry
                .counter(
                        AgentMetrics.AGENT_IMAGE_CONTRACT,
                        "outcome",
                        outcome.name().toLowerCase(Locale.ROOT))
                .increment();
        return outcome;
    }

    private Outcome evaluate(String image) {
        Optional<Map<String, String>> labels = imageOps.imageLabels(image);
        if (labels.isEmpty()) {
            log.warn("Could not inspect agent image {}; its runtime contract is unverified.", image);
            return Outcome.UNKNOWN;
        }
        String declared = labels.get().get(SandboxLayout.RUNTIME_CONTRACT_LABEL);
        int expected = SandboxLayout.RUNTIME_CONTRACT_VERSION;
        if (declared == null) {
            log.error(
                    "Agent image {} declares no {} label, so it predates the runtime contract this server stages "
                            + "for (v{}). It cannot be a build matching this server. See docs/admin/release-image-lock.md.",
                    image,
                    SandboxLayout.RUNTIME_CONTRACT_LABEL,
                    expected);
            return Outcome.UNLABELLED;
        }
        if (!declared.equals(Integer.toString(expected))) {
            log.error(
                    "Agent image {} implements runtime contract v{} but this server stages for v{} (image node={}, pi={}). "
                            + "Practice reviews and mentor sessions in this image will fail. See docs/admin/release-image-lock.md.",
                    image,
                    declared,
                    expected,
                    labels.get().getOrDefault(NODE_VERSION_LABEL, "unknown"),
                    labels.get().getOrDefault(PI_VERSION_LABEL, "unknown"));
            return Outcome.MISMATCH;
        }
        log.info("Agent image {} implements runtime contract v{}.", image, expected);
        return Outcome.VERIFIED;
    }
}
