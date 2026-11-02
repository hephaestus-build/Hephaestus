package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import io.fabric8.kubernetes.api.model.Toleration;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;

/**
 * The worker's Kubernetes sandbox runtime. A missing or invalid value
 * stops the worker's start. The chart supplies its Pod metadata through the Downward API. Host development supplies the worktree's
 * identity and the k3d host address through the dev launcher.
 *
 * @param namespace the namespace of every sandbox Pod and Job. The installation owns it alone, so everything in it of
 *     the sandbox kinds is this installation's
 * @param podName this worker's Pod name, or its worktree identity during host development
 * @param podIp the address where sandboxes reach this worker: its Pod IP, or the k3d host address during development
 * @param runtimeClassName the RuntimeClass of every sandbox Pod. Blank selects the cluster's default.
 *     It must support user namespaces. A missing or incompatible class fails the isolation probe
 * @param seccompProfile the shipped {@code sandbox/agent-seccomp-profile.json} as a Localhost profile, relative to the
 *     kubelet's seccomp directory. Blank selects {@code RuntimeDefault}
 * @param nodeSelector the node labels of sandbox Pods, as a JSON object. Empty allows any node.
 * @param tolerations the tolerations of sandbox Pods, as a JSON array of Kubernetes tolerations. Empty tolerates no taint.
 * @param conversationPriorityClass the PriorityClass of the conversation host and turn sandbox Pods. Blank gives none
 * @param reviewPriorityClass the PriorityClass of review and probe Pods, below the conversations': a review is an
 *     attempt that runs again, a conversation is a person waiting. Blank gives none
 * @param probeInterval how often the isolation probe runs again after the worker's start
 */
@Validated
@Profile("!specs & !cds-training")
@ConditionalOnWorkerRole
@ConfigurationProperties(prefix = "hephaestus.sandbox.kubernetes", ignoreUnknownFields = false)
public record KubernetesSandboxProperties(
        @DefaultValue("") @NotBlank @Pattern(regexp = DNS_LABEL)
        String namespace,

        @DefaultValue("") @NotBlank @Pattern(regexp = DNS_SUBDOMAIN)
        String podName,

        @DefaultValue("") @NotBlank String podIp,

        @DefaultValue("") @Pattern(regexp = "|" + DNS_SUBDOMAIN)
        String runtimeClassName,

        @DefaultValue("") @Pattern(regexp = "|" + LOCALHOST_PROFILE)
        String seccompProfile,

        @DefaultValue("") String nodeSelector,
        @DefaultValue("") String tolerations,

        @DefaultValue("") @Pattern(regexp = "|" + DNS_SUBDOMAIN)
        String conversationPriorityClass,

        @DefaultValue("") @Pattern(regexp = "|" + DNS_SUBDOMAIN)
        String reviewPriorityClass,

        @DefaultValue("15m") Duration probeInterval) {

    private static final KubernetesSerialization JSON = new KubernetesSerialization();

    static final String DNS_LABEL = "[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?";

    static final String DNS_SUBDOMAIN = "[a-z0-9]([-a-z0-9.]{0,251}[a-z0-9])?";

    /** Relative to the kubelet's seccomp directory, without a step out of it. */
    static final String LOCALHOST_PROFILE = "(?!.*(^|/)\\.\\.(/|$))[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*";

    @AssertTrue(message = "pod-ip must be an IPv4 or IPv6 address without a zone")
    private boolean isPodIpLiteral() {
        return isIpv4(podIp) || isIpv6(podIp);
    }

    @AssertTrue(message = "node-selector must be a JSON object of strings")
    private boolean isNodeSelectorValid() {
        try {
            nodeSelectorMap();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @AssertTrue(message = "tolerations must be a JSON array of Kubernetes tolerations")
    private boolean isTolerationsValid() {
        try {
            tolerationList();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @AssertTrue(message = "probe-interval must be at least one minute")
    private boolean isProbeIntervalValid() {
        return probeInterval.compareTo(Duration.ofMinutes(1)) >= 0;
    }

    Map<String, String> nodeSelectorMap() {
        if (nodeSelector.isBlank()) {
            return Map.of();
        }
        Map<?, ?> parsed = JSON.unmarshal(nodeSelector, Map.class);
        Map<String, String> labels = new HashMap<>();
        parsed.forEach((key, value) -> {
            if (!(key instanceof String name) || !(value instanceof String label)) {
                throw new IllegalArgumentException("A node selector maps label names to label values");
            }
            labels.put(name, label);
        });
        return Map.copyOf(labels);
    }

    List<Toleration> tolerationList() {
        return tolerations.isBlank() ? List.of() : List.of(JSON.unmarshal(tolerations, Toleration[].class));
    }

    /** Dotted decimal, four octets, as the Downward API writes {@code status.podIP}. */
    private static boolean isIpv4(String value) {
        String[] octets = value.split("\\.", -1);
        return octets.length == 4
                && Arrays.stream(octets).allMatch(octet -> octet.matches("25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9]"));
    }

    /**
     * The JDK's own IPv6 literal parser. A value that starts with a hexadecimal digit or a colon and contains a colon is
     * only ever parsed as a literal by {@link InetAddress#getByName}, never looked up: a name could resolve to another
     * worker. A zone ({@code %}) is not an address a Pod has.
     */
    private static boolean isIpv6(String value) {
        if (!value.matches("[0-9A-Fa-f:][0-9A-Fa-f:.]{1,44}") || value.indexOf(':') < 0) {
            return false;
        }
        try {
            InetAddress.getByName(value);
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** The host of this worker's gateway in a URL: the address itself, an IPv6 address in brackets. */
    String gatewayHost() {
        return podIp.indexOf(':') >= 0 ? "[" + podIp + "]" : podIp;
    }
}
