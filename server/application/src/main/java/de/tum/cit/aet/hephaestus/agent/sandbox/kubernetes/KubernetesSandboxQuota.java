package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How many more review sandboxes the ResourceQuotas of the sandbox namespace admit now. The API server refuses a Pod
 * that a quota cannot hold, and a review Job's Pod then never starts; a worker that asks first leaves the review in its
 * queue instead. The chart renders one quota; an operator's own quotas count the same way. Another sandbox, such as a
 * Heph turn's, can take the room between the answer and the Pod, so the answer bounds what is claimed, nothing more.
 */
public class KubernetesSandboxQuota {

    private static final Logger log = LoggerFactory.getLogger(KubernetesSandboxQuota.class);

    private final KubernetesClient client;
    private final String namespace;
    private final AtomicBoolean unreadable = new AtomicBoolean();
    private final AtomicBoolean full = new AtomicBoolean();

    public KubernetesSandboxQuota(KubernetesClient client, String namespace) {
        this.client = client;
        this.namespace = namespace;
    }

    /** @return {@link Integer#MAX_VALUE} without a quota, or when the quotas cannot be read: as before, a start decides */
    public int roomFor(ResourceLimits limits) {
        List<ResourceQuota> quotas;
        try {
            quotas = client.resourceQuotas().inNamespace(namespace).list().getItems();
        } catch (RuntimeException e) {
            if (unreadable.compareAndSet(false, true)) {
                log.warn(
                        "Sandbox quota unreadable, so reviews are claimed without it: namespace={}, error={}",
                        namespace,
                        e.getClass().getSimpleName());
            }
            return Integer.MAX_VALUE;
        }
        unreadable.set(false);
        int room = fits(quotas, footprint(limits));
        if (room == 0 && full.compareAndSet(false, true)) {
            log.info("Reviews wait for room in the sandbox quota: namespace={}", namespace);
        } else if (room > 0 && full.compareAndSet(true, false)) {
            log.info("The sandbox quota has room for reviews again: namespace={}", namespace);
        }
        return room;
    }

    /**
     * What one review sandbox counts against a quota, by the quota's resource names: its Pod and its Job, and the
     * resources of its runtime container ({@link KubernetesPods#resources}). The initializer has the same resources and
     * runs before the runtime, and the isolation gate is smaller, so the runtime's are the Pod's.
     */
    static Map<String, Quantity> footprint(ResourceLimits limits) {
        var resources = KubernetesPods.resources(limits);
        Quantity one = new Quantity("1");
        Quantity cpuRequest = resources.getRequests().get("cpu");
        Quantity memoryRequest = resources.getRequests().get("memory");
        return Map.of(
                "pods", one,
                "count/jobs.batch", one,
                "cpu", cpuRequest,
                "requests.cpu", cpuRequest,
                "limits.cpu", resources.getLimits().get("cpu"),
                "memory", memoryRequest,
                "requests.memory", memoryRequest,
                "limits.memory", resources.getLimits().get("memory"));
    }

    /** The fewest whole footprints that any quota still holds; a resource the footprint does not use never bounds it. */
    static int fits(List<ResourceQuota> quotas, Map<String, Quantity> footprint) {
        int room = Integer.MAX_VALUE;
        for (ResourceQuota quota : quotas) {
            if (quota.getStatus() == null || quota.getStatus().getHard() == null || scoped(quota)) continue;
            Map<String, Quantity> used = quota.getStatus().getUsed() == null
                    ? Map.of()
                    : quota.getStatus().getUsed();
            for (var hard : quota.getStatus().getHard().entrySet()) {
                Quantity need = footprint.get(hard.getKey());
                if (need == null) continue;
                BigDecimal free = hard.getValue()
                        .getNumericalAmount()
                        .subtract(
                                used.containsKey(hard.getKey())
                                        ? used.get(hard.getKey()).getNumericalAmount()
                                        : BigDecimal.ZERO);
                BigDecimal each = need.getNumericalAmount();
                int fit = free.signum() <= 0
                        ? 0
                        : free.divide(each, 0, RoundingMode.FLOOR)
                                .min(BigDecimal.valueOf(Integer.MAX_VALUE))
                                .intValue();
                room = Math.min(room, fit);
            }
        }
        return room;
    }

    /** A scoped quota counts only some Pods; whether a review's is one, its admission decides, not this estimate. */
    private static boolean scoped(ResourceQuota quota) {
        var spec = quota.getSpec();
        return spec != null
                && ((spec.getScopes() != null && !spec.getScopes().isEmpty()) || spec.getScopeSelector() != null);
    }
}
