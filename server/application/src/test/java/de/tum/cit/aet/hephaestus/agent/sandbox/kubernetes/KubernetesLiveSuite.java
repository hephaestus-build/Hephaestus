package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs the credential-free live suite as the chart worker Pod's only JVM. */
public final class KubernetesLiveSuite {
    private static final Logger log = LoggerFactory.getLogger(KubernetesLiveSuite.class);

    private KubernetesLiveSuite() {}

    public static void main(String[] args) throws Exception {
        if (!"true".equals(System.getenv("HEPHAESTUS_KUBERNETES_LIVE"))) {
            throw new IllegalStateException("Kubernetes qualification requires HEPHAESTUS_KUBERNETES_LIVE=true");
        }
        log.info(
                "Kubernetes live qualification kernel={}",
                Files.readString(Path.of("/proc/sys/kernel/osrelease")).strip());
        int port = Integer.parseInt(System.getenv().getOrDefault("MANAGEMENT_PORT", "9090"));
        // The unchanged chart probes keep this test JVM alive. Sandbox reachability of this listener is still denied
        // and proved by the real isolation probe; this is not an application readiness qualification.
        var health = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            health.setExecutor(executor);
            health.createContext("/actuator/health/", exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(200, -1);
                }
            });
            health.start();
            try {
                var listener = new SummaryGeneratingListener();
                LauncherFactory.create()
                        .execute(
                                request()
                                        .selectors(selectClass(KubernetesSandboxLiveTest.class))
                                        .build(),
                                listener);
                var summary = listener.getSummary();
                summary.getFailures()
                        .forEach(failure -> log.error(
                                "Kubernetes live case failed: {}",
                                failure.getTestIdentifier().getDisplayName(),
                                failure.getException()));
                log.info(
                        "KUBERNETES_LIVE_RESULT found={} passed={} failed={} skipped={} aborted={} containerFailures={}",
                        summary.getTestsFoundCount(),
                        summary.getTestsSucceededCount(),
                        summary.getTestsFailedCount(),
                        summary.getTestsSkippedCount(),
                        summary.getTestsAbortedCount(),
                        summary.getContainersFailedCount());
                if (summary.getTestsFoundCount() == 0
                        || summary.getTestsSucceededCount() != summary.getTestsFoundCount()
                        || summary.getTestsSkippedCount() != 0
                        || summary.getTestsAbortedCount() != 0
                        || summary.getContainersFailedCount() != 0
                        || summary.getContainersSkippedCount() != 0
                        || summary.getContainersAbortedCount() != 0) {
                    throw new IllegalStateException("Kubernetes live qualification did not pass every selected case");
                }
            } finally {
                health.stop(0);
            }
        }
    }
}
