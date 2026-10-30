package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
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

    private final KubernetesSandboxCache cache;
    private final String namespace;
    private final String runtimeClassName;
    private final String reviewPriorityClass;
    private final AtomicBoolean unreadable = new AtomicBoolean();
    private final AtomicBoolean full = new AtomicBoolean();
    private final AtomicBoolean tooSmall = new AtomicBoolean();

    /** The RuntimeClass's Pod overhead once a sandbox Pod showed it; fixed for the class. */
    private volatile @Nullable Map<String, Quantity> overhead;

    /**
     * @param runtimeClassName the RuntimeClass of the sandbox Pods; blank for the cluster's default
     * @param reviewPriorityClass the PriorityClass of review Pods, whose tier quota counts them; blank for none
     */
    public KubernetesSandboxQuota(
            KubernetesSandboxCache cache, String namespace, String runtimeClassName, String reviewPriorityClass) {
        this.cache = cache;
        this.namespace = namespace;
        this.runtimeClassName = runtimeClassName;
        this.reviewPriorityClass = reviewPriorityClass;
    }

    /** @return {@link Integer#MAX_VALUE} without a quota, or when the quotas cannot be read: as before, a start decides */
    public int roomFor(ResourceLimits limits) {
        List<ResourceQuota> quotas;
        try {
            quotas = cache.quotas();
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
        int room = fits(quotas, footprint(limits, overhead()), reviewPriorityClass);
        if (room == 0 && full.compareAndSet(false, true)) {
            log.info("Reviews wait for room in the sandbox quota: namespace={}", namespace);
        } else if (room > 0 && full.compareAndSet(true, false)) {
            log.info("The sandbox quota has room for reviews again: namespace={}", namespace);
        }
        return room;
    }

    /**
     * Whether a quota of the namespace is smaller than one review sandbox, so that no wait gives it room. False when the
     * quotas cannot be read: a start then decides, as for {@link #roomFor}. Warns once each time it becomes true.
     */
    public boolean neverFits(ResourceLimits limits) {
        List<ResourceQuota> quotas;
        try {
            quotas = cache.quotas();
        } catch (RuntimeException e) {
            return false;
        }
        boolean never = neverFits(quotas, footprint(limits, overhead()), reviewPriorityClass);
        if (never && tooSmall.compareAndSet(false, true)) {
            log.warn(
                    "A sandbox quota is smaller than one review sandbox, so reviews are cancelled: raise"
                            + " a sandbox quota of your own (docs/admin/kubernetes.mdx). namespace={}",
                    namespace);
        } else if (!never) {
            tooSmall.set(false);
        }
        return never;
    }

    /** Whether a review quota's hard bound of a resource that the footprint uses is below one footprint. */
    static boolean neverFits(List<ResourceQuota> quotas, Map<String, Quantity> footprint, String reviewClass) {
        for (ResourceQuota quota : quotas) {
            if (quota.getStatus() == null || quota.getStatus().getHard() == null || !countsReviews(quota, reviewClass))
                continue;
            for (var hard : quota.getStatus().getHard().entrySet()) {
                Quantity need = footprint.get(hard.getKey());
                if (need != null && hard.getValue().getNumericalAmount().compareTo(need.getNumericalAmount()) < 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The Pod overhead of the sandbox RuntimeClass, which a quota charges beside the containers' resources. The
     * RuntimeClass admission writes it into each Pod it admits, so a sandbox Pod of the class shows it, and the worker
     * needs no right to read the cluster-wide RuntimeClass. Until such a Pod exists, it counts none: the admission then
     * decides, as before.
     */
    private Map<String, Quantity> overhead() {
        Map<String, Quantity> known = overhead;
        if (known != null) return known;
        if (runtimeClassName.isBlank()) return Map.of();
        try {
            for (Pod pod : cache.pods(Map.of())) {
                var spec = pod.getSpec();
                if (spec != null && runtimeClassName.equals(spec.getRuntimeClassName())) {
                    Map<String, Quantity> shown =
                            spec.getOverhead() == null ? Map.of() : Map.copyOf(spec.getOverhead());
                    overhead = shown;
                    return shown;
                }
            }
        } catch (RuntimeException e) {
            log.debug(
                    "Sandbox Pods unreadable for the Pod overhead: error={}",
                    e.getClass().getSimpleName());
        }
        return Map.of();
    }

    /**
     * What one review sandbox counts against a quota, by the quota's resource names: its Pod and its Job, the
     * resources of its runtime container at the review's size ({@link KubernetesPods#resources},
     * {@link KubernetesPods#REVIEW}), and the Pod overhead of its RuntimeClass, which a quota adds to both requests and
     * limits. The initializer has the same resources and runs before the runtime, and the isolation gate is smaller,
     * so the runtime's are the Pod's.
     */
    static Map<String, Quantity> footprint(ResourceLimits limits, Map<String, Quantity> overhead) {
        var resources = KubernetesPods.resources(limits, KubernetesPods.REVIEW);
        Quantity one = new Quantity("1");
        Quantity cpuRequest = plus(resources.getRequests().get("cpu"), overhead.get("cpu"));
        Quantity cpuLimit = plus(resources.getLimits().get("cpu"), overhead.get("cpu"));
        Quantity memoryRequest = plus(resources.getRequests().get("memory"), overhead.get("memory"));
        Quantity memoryLimit = plus(resources.getLimits().get("memory"), overhead.get("memory"));
        Quantity disk = Objects.requireNonNull(resources.getRequests().get("ephemeral-storage"));
        Quantity diskLimit = Objects.requireNonNull(resources.getLimits().get("ephemeral-storage"));
        return Map.ofEntries(
                Map.entry("pods", one),
                Map.entry("count/pods", one),
                Map.entry("count/jobs.batch", one),
                Map.entry("cpu", cpuRequest),
                Map.entry("requests.cpu", cpuRequest),
                Map.entry("limits.cpu", cpuLimit),
                Map.entry("memory", memoryRequest),
                Map.entry("requests.memory", memoryRequest),
                Map.entry("limits.memory", memoryLimit),
                Map.entry("ephemeral-storage", disk),
                Map.entry("requests.ephemeral-storage", disk),
                Map.entry("limits.ephemeral-storage", diskLimit));
    }

    private static Quantity plus(@Nullable Quantity amount, @Nullable Quantity overhead) {
        Quantity base = Objects.requireNonNull(amount, "KubernetesPods.resources sets every CPU and memory amount");
        return overhead == null
                ? base
                : new Quantity(base.getNumericalAmount()
                        .add(overhead.getNumericalAmount())
                        .toPlainString());
    }

    /** The fewest whole footprints that any quota still holds; a resource the footprint does not use never bounds it. */
    static int fits(List<ResourceQuota> quotas, Map<String, Quantity> footprint, String reviewClass) {
        int room = Integer.MAX_VALUE;
        for (ResourceQuota quota : quotas) {
            if (quota.getStatus() == null || quota.getStatus().getHard() == null || !countsReviews(quota, reviewClass))
                continue;
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

    /**
     * Whether a quota counts a review's Pod: it has no scope, or it selects the reviews' PriorityClass, as the chart's
     * reviews tier does. Any other scope counts only some Pods; whether a review's is one, its admission decides, not
     * this estimate.
     */
    static boolean countsReviews(ResourceQuota quota, String reviewClass) {
        var spec = quota.getSpec();
        if (spec == null) return true;
        if (spec.getScopes() != null && !spec.getScopes().isEmpty()) return false;
        var selector = spec.getScopeSelector();
        if (selector == null
                || selector.getMatchExpressions() == null
                || selector.getMatchExpressions().isEmpty()) return true;
        return !reviewClass.isEmpty()
                && selector.getMatchExpressions().stream()
                        .allMatch(expression -> "PriorityClass".equals(expression.getScopeName())
                                && "In".equals(expression.getOperator())
                                && expression.getValues() != null
                                && expression.getValues().contains(reviewClass));
    }
}
