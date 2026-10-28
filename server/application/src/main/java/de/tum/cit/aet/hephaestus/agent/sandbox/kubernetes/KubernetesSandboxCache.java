package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The sandbox namespace as the worker's informers see it: its Pods, Jobs and ResourceQuotas. A claim poll, a room
 * check and the readiness probe read here, so they send nothing to the API server, and a slow API server does not slow
 * them. The namespace holds this release's sandboxes alone, so the caches stay small. Writes, and the waits for one
 * Pod, still go to the API server.
 */
public class KubernetesSandboxCache implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KubernetesSandboxCache.class);

    private final String namespace;
    private final SharedIndexInformer<Pod> pods;
    private final SharedIndexInformer<Job> jobs;
    private final SharedIndexInformer<ResourceQuota> quotas;

    /** Starts the informers; each lists once and then watches. A read before the first list completes fails. */
    public KubernetesSandboxCache(KubernetesClient client, String namespace) {
        this.namespace = namespace;
        // A resync of 0: the cache only changes on the API server's events, and no handler needs a periodic replay.
        // Only sandbox objects: a foreign object in the namespace cannot grow the caches. Quotas are not selected,
        // because an operator's own quota counts as the chart's does.
        this.pods = start(client.pods()
                .inNamespace(namespace)
                .withLabel(KubernetesWorker.PART_OF, "hephaestus")
                .runnableInformer(0));
        this.jobs = start(client.batch()
                .v1()
                .jobs()
                .inNamespace(namespace)
                .withLabel(KubernetesWorker.PART_OF, "hephaestus")
                .runnableInformer(0));
        this.quotas = start(client.resourceQuotas().inNamespace(namespace).runnableInformer(0));
    }

    private <T extends HasMetadata> SharedIndexInformer<T> start(SharedIndexInformer<T> informer) {
        // The client's default stops an informer for good when its first list fails, or on some watch errors: an API
        // server that is down at start, or a missing verb, would then leave the worker unready until a restart.
        informer.exceptionHandler((started, failure) -> {
            log.warn(
                    "A sandbox namespace informer failed and retries: namespace={}, status={}, error={}",
                    namespace,
                    failure instanceof KubernetesClientException api ? api.getCode() : 0,
                    failure.getClass().getSimpleName());
            return true;
        });
        var unused = informer.start();
        return informer;
    }

    /** Whether every cache has listed the namespace once and is watching it now. */
    public boolean current() {
        return List.of(pods, jobs, quotas).stream().allMatch(informer -> informer.hasSynced() && informer.isWatching());
    }

    /** The sandbox Pods that carry every one of the labels. */
    public List<Pod> pods(Map<String, String> labels) {
        return matching(pods, labels);
    }

    /** The sandbox Jobs that carry every one of the labels. */
    public List<Job> jobs(Map<String, String> labels) {
        return matching(jobs, labels);
    }

    public List<ResourceQuota> quotas() {
        return List.copyOf(synced(quotas).getStore().list());
    }

    private <T extends HasMetadata> List<T> matching(SharedIndexInformer<T> informer, Map<String, String> labels) {
        return synced(informer).getStore().list().stream()
                .filter(item -> {
                    Map<String, String> carried = item.getMetadata().getLabels();
                    return carried != null && carried.entrySet().containsAll(labels.entrySet());
                })
                .toList();
    }

    /**
     * A cache that does not watch the namespace now does not know it: before its first list, while its watch
     * reconnects, and after it stopped. The caller then fails as on an API error, as readiness does, so no admission
     * acts on what may have changed.
     */
    private <T extends HasMetadata> SharedIndexInformer<T> synced(SharedIndexInformer<T> informer) {
        if (!informer.hasSynced() || !informer.isWatching()) {
            throw new KubernetesClientException("The sandbox namespace cache has not listed the namespace yet");
        }
        return informer;
    }

    @Override
    public void close() {
        List.of(pods, jobs, quotas).forEach(SharedIndexInformer::close);
    }
}
