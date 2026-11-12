package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.util.function.Consumer;

/**
 * A mock API client that makes no request retries. A test that answers 5xx means the failure. With the client's own
 * retries, each such request waits through ten doubling delays before it fails, and retries still pending when the test
 * closes its client fail into later tests. Public with a public constructor, since the mock extension creates it.
 */
public final class NoRequestRetries implements Consumer<KubernetesClientBuilder> {

    @Override
    public void accept(KubernetesClientBuilder builder) {
        builder.editOrNewConfig().withRequestRetryBackoffLimit(0).endConfig();
    }
}
