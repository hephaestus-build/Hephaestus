package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInfrastructureException;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * This worker as its sandboxes record it: the labels of every sandbox it creates, the address of its gateway, and what
 * the last isolation probe proved.
 *
 * <p>Every sandbox records the review ledger's id of the worker process that created it ({@code WorkerProperties}),
 * the id that the worker's heartbeat in {@code worker_registry} carries. A sandbox whose worker no longer beats belongs
 * to no running worker. Any number of workers share the sandbox namespace on these terms.
 */
public class KubernetesWorker {

    public static final String PART_OF = "app.kubernetes.io/part-of";
    public static final String SANDBOX_KIND = "hephaestus.build/sandbox-kind";

    /** The ledger id of the worker process that created a sandbox. */
    public static final String WORKER_ID = "hephaestus.build/worker-id";

    /** The worker Pod that created a sandbox: a restart of the worker container keeps it. */
    public static final String WORKER_POD = "hephaestus.build/worker-pod";

    public static final String KIND_REVIEW = "review";
    public static final String KIND_HOST = "host";
    public static final String KIND_TURN = "turn";

    /** A Pod of the isolation probe: the chart's policies treat it as a review Pod or a turn Pod, by its kind. */
    public static final String PROBE = "hephaestus.build/isolation-probe";

    public static final String JOB_ID = "hephaestus.build/job-id";
    public static final String JOB_ATTEMPT = "hephaestus.build/job-attempt";
    public static final String SESSION_ID = "hephaestus.build/session-id";
    public static final String SANDBOX_ID = "hephaestus.build/sandbox-id";

    private final KubernetesSandboxProperties properties;
    private final String workerId;
    private final int gatewayPort;
    private volatile KubernetesIsolationProbe.@Nullable Proof proof;

    public KubernetesWorker(KubernetesSandboxProperties properties, String workerId, int gatewayPort) {
        this.properties = properties;
        this.workerId = workerId;
        this.gatewayPort = gatewayPort;
    }

    public KubernetesSandboxProperties properties() {
        return properties;
    }

    public String namespace() {
        return properties.namespace();
    }

    /** The ledger id of this worker process. */
    public String workerId() {
        return workerId;
    }

    /** The gateway as this worker's sandboxes reach it: the worker Pod's own address, never a Service. */
    public String gatewayUrl() {
        return "http://" + properties.gatewayHost() + ":" + gatewayPort;
    }

    public int gatewayPort() {
        return gatewayPort;
    }

    /** The labels of a new sandbox of the kind. */
    public Map<String, String> labels(String kind) {
        Map<String, String> labels = new HashMap<>(selector(kind));
        labels.put(WORKER_ID, labelValue(workerId));
        labels.put(WORKER_POD, labelValue(properties.podName()));
        return labels;
    }

    /** The labels that select the sandboxes of the kind. */
    public Map<String, String> selector(String kind) {
        Map<String, String> selector = new HashMap<>();
        selector.put(PART_OF, "hephaestus");
        selector.put(SANDBOX_KIND, kind);
        return selector;
    }

    /** Whether this process created the sandbox of these labels. */
    public boolean createdBySelf(Map<String, String> labels) {
        return labelValue(workerId).equals(labels.get(WORKER_ID));
    }

    /**
     * A ledger id as a label value: at most 63 characters of letters, digits, {@code -}, {@code _} and {@code .}. The
     * worker ids that the ledger generates are such values already.
     */
    static String labelValue(String id) {
        String cleaned = id.replaceAll("[^A-Za-z0-9._-]", "-");
        cleaned = cleaned.length() > 63 ? cleaned.substring(cleaned.length() - 63) : cleaned;
        return cleaned.replaceAll("^[^A-Za-z0-9]+|[^A-Za-z0-9]+$", "");
    }

    /** Records what the latest isolation probe proved ({@link KubernetesIsolationProbe}). */
    void proved(KubernetesIsolationProbe.Proof latest) {
        proof = latest;
    }

    /** What the latest isolation probe proved, or null before the first one ended. */
    public KubernetesIsolationProbe.@Nullable Proof proof() {
        return proof;
    }

    public boolean isolated() {
        KubernetesIsolationProbe.Proof latest = proof;
        return latest != null && latest.passed();
    }

    /** Whether the latest probe showed that user namespaces take effect. */
    public boolean userNamespaces() {
        KubernetesIsolationProbe.Proof latest = proof;
        return latest != null && latest.userNamespaces();
    }

    /**
     * Refuses every sandbox until the isolation probe passed, and again while its latest run failed. The refusal is the
     * runtime's, so the work is tried again later.
     */
    public void requireIsolation() {
        if (!isolated()) {
            throw new SandboxInfrastructureException("The cluster has not passed the sandbox isolation probe");
        }
    }
}
