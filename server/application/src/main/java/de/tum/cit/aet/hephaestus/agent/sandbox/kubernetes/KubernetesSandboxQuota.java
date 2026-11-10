package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Whether a sandbox Pod fits the ResourceQuotas of the sandbox namespace. The API server refuses a Pod that a quota cannot
 * hold, and the Pod of its Job then never starts, so a tools Pod asks first. The chart renders a review-tier quota; an
 * operator's own quotas count the same way.
 */
final class KubernetesSandboxQuota {

    private KubernetesSandboxQuota() {}

    /** The room when the quota has no current observation. */
    public static final int UNKNOWN_ROOM = -1;

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

    /** The effective requests and limits of a tools Pod, with the RuntimeClass overhead seen in the cache. */
    static Map<String, Quantity> footprint(Pod pod, Map<String, Quantity> overhead) {
        Map<String, Quantity> footprint = new HashMap<>();
        footprint.put("pods", new Quantity("1"));
        footprint.put("count/pods", new Quantity("1"));
        for (String type : List.of("requests", "limits")) {
            for (String resource : List.of("cpu", "memory", "ephemeral-storage")) {
                Function<Container, BigDecimal> amount = container -> {
                    var resources = container.getResources();
                    var values = "requests".equals(type) ? resources.getRequests() : resources.getLimits();
                    Quantity quantity = values.get(resource);
                    return quantity == null ? BigDecimal.ZERO : quantity.getNumericalAmount();
                };
                BigDecimal running =
                        pod.getSpec().getContainers().stream().map(amount).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal init =
                        pod.getSpec().getInitContainers().stream().map(amount).reduce(BigDecimal.ZERO, BigDecimal::max);
                Quantity extra = overhead.get(resource);
                BigDecimal total = running.max(init).add(extra == null ? BigDecimal.ZERO : extra.getNumericalAmount());
                Quantity value = new Quantity(total.toPlainString());
                footprint.put(type + "." + resource, value);
                if ("requests".equals(type)) footprint.put(resource, value);
            }
        }
        return footprint;
    }

    /** The fewest whole footprints that any quota still holds; a resource the footprint does not use never bounds it. */
    static int fits(List<ResourceQuota> quotas, Map<String, Quantity> footprint, String reviewClass) {
        int room = Integer.MAX_VALUE;
        for (ResourceQuota quota : quotas) {
            if (!countsReviews(quota, reviewClass)) continue;
            var status = quota.getStatus();
            if (status == null || status.getHard() == null || status.getHard().isEmpty()) return UNKNOWN_ROOM;
            Map<String, Quantity> used = status.getUsed();
            for (var hard : status.getHard().entrySet()) {
                Quantity need = footprint.get(hard.getKey());
                if (need == null) continue;
                // A new or reconciling quota has no usage observation yet; absence is not zero usage.
                if (used == null || !used.containsKey(hard.getKey())) return UNKNOWN_ROOM;
                BigDecimal free = hard.getValue()
                        .getNumericalAmount()
                        .subtract(used.get(hard.getKey()).getNumericalAmount());
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
