package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInfrastructureException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages Docker container lifecycle for sandbox execution.
 *
 * <p>Handles container creation, starting, waiting for completion with timeout enforcement, log
 * collection, and removal.
 */
public class SandboxContainerManager {

    private static final Logger log = LoggerFactory.getLogger(SandboxContainerManager.class);
    private static final Duration POST_STOP_WAIT_TIMEOUT = Duration.ofSeconds(30);
    private static final int SIGKILL_EXIT_CODE = 137;

    private final DockerContainerOperations containerOps;
    private final SandboxImageGuard imageGuard;
    private final SandboxProperties properties;
    private final String owner;

    /**
     * Dedicated platform thread pool for {@code docker wait} calls.
     *
     * <p>docker-java's Apache HttpClient5 uses {@code synchronized} blocks that pin virtual threads
     * on Java 21. A dedicated bounded pool avoids starving the common ForkJoinPool. Managed by Spring
     * via {@code destroyMethod="shutdown"} on the bean definition.
     */
    private final ExecutorService dockerWaitExecutor;

    private final Duration postStopWaitTimeout;

    public SandboxContainerManager(
            DockerContainerOperations containerOps,
            SandboxImageGuard imageGuard,
            SandboxProperties properties,
            String owner,
            ExecutorService dockerWaitExecutor) {
        this(containerOps, imageGuard, properties, owner, dockerWaitExecutor, POST_STOP_WAIT_TIMEOUT);
    }

    SandboxContainerManager(
            DockerContainerOperations containerOps,
            SandboxImageGuard imageGuard,
            SandboxProperties properties,
            String owner,
            ExecutorService dockerWaitExecutor,
            Duration postStopWaitTimeout) {
        this.containerOps = containerOps;
        this.imageGuard = imageGuard;
        this.properties = properties;
        this.owner = owner;
        this.dockerWaitExecutor = dockerWaitExecutor;
        this.postStopWaitTimeout = postStopWaitTimeout;
    }

    /**
     * Create a container from the given spec.
     *
     * @return the container ID
     */
    public String createContainer(DockerOperations.ContainerSpec spec) {
        imageGuard.ensurePresent(spec.image());
        return containerOps.createContainer(spec);
    }

    /** Start a created container. */
    public void startContainer(String containerId) {
        containerOps.startContainer(containerId);
    }

    /**
     * Wait for container completion with timeout enforcement.
     *
     * <p>If the container exceeds the timeout, it is stopped (SIGTERM → grace period → SIGKILL) and
     * this method returns with {@code timedOut=true}.
     *
     * @param containerId the container to wait for
     * @param timeout maximum allowed execution time
     * @return the wait result including exit code and whether it timed out
     */
    public WaitOutcome waitForCompletion(String containerId, Duration timeout) {
        // Use ExecutorService.submit() (not CompletableFuture.supplyAsync()) so that
        // Future.cancel(true) actually interrupts the blocking docker-wait thread.
        // CompletableFuture.cancel(true) does NOT propagate interruption, which would
        // exhaust the bounded thread pool under sustained cancellation/timeout.
        Future<DockerOperations.WaitResult> waitFuture =
                dockerWaitExecutor.submit(() -> containerOps.waitContainer(containerId));

        try {
            DockerOperations.WaitResult result = waitFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new WaitOutcome(result.exitCode(), false);
        } catch (TimeoutException e) {
            log.warn("Container {} exceeded timeout of {}, stopping", containerId, timeout);
            try {
                containerOps.stopContainer(containerId, properties.containerStopTimeoutSeconds());
            } catch (Exception stopEx) {
                log.error("Failed to stop timed-out container {}: {}", containerId, stopEx.getMessage());
            }
            // Wait for the stop to take effect
            try {
                DockerOperations.WaitResult result =
                        waitFuture.get(postStopWaitTimeout.toMillis(), TimeUnit.MILLISECONDS);
                return new WaitOutcome(result.exitCode(), true);
            } catch (InterruptedException ex) {
                waitFuture.cancel(true);
                Thread.currentThread().interrupt();
                return new WaitOutcome(SIGKILL_EXIT_CODE, true);
            } catch (Exception ex) {
                waitFuture.cancel(true);
                return new WaitOutcome(SIGKILL_EXIT_CODE, true);
            }
        } catch (CancellationException e) {
            throw new SandboxInfrastructureException("Wait cancelled for container: " + containerId, e);
        } catch (InterruptedException e) {
            waitFuture.cancel(true);
            Thread.currentThread().interrupt();
            throw new SandboxInfrastructureException("Interrupted while waiting for container: " + containerId, e);
        } catch (ExecutionException e) {
            throw new SandboxInfrastructureException("Error waiting for container: " + containerId, e);
        }
    }

    /**
     * Get the last N lines of container logs.
     *
     * @param containerId the container
     * @param tailLines number of lines (0 = all)
     * @return combined stdout + stderr
     */
    public String getLogs(String containerId, int tailLines) {
        try {
            return containerOps.getLogs(containerId, tailLines);
        } catch (Exception e) {
            log.warn("Failed to get logs for container {}: {}", containerId, e.getMessage());
            return "";
        }
    }

    /** Force-remove a container. Idempotent. */
    public void forceRemove(String containerId) {
        containerOps.removeContainer(containerId, true);
    }

    /** List containers owned by this installation. */
    public List<DockerOperations.ContainerInfo> listManagedContainers() {
        return containerOps.listContainersByLabel(SandboxLabels.OWNER, owner);
    }

    /**
     * Stop a running container (SIGTERM → grace period → SIGKILL).
     *
     * @param containerId the container to stop
     */
    public void stopContainer(String containerId) {
        containerOps.stopContainer(containerId, properties.containerStopTimeoutSeconds());
    }

    /**
     * Stop a running container with an explicit grace timeout (SIGTERM → {@code graceSeconds} →
     * SIGKILL).
     *
     * <p>Used by the interactive sandbox where each session may pick its own grace deadline (the
     * issue's contract: "wait up to {@code graceTimeout} for the in-flight turn to finish"). The
     * argument-less overload uses the global {@code hephaestus.sandbox.container-stop-timeout-seconds}.
     */
    public void stopContainer(String containerId, int graceSeconds) {
        containerOps.stopContainer(containerId, graceSeconds);
    }

    /** Check if the Docker daemon is reachable. */
    public boolean ping() {
        return containerOps.ping();
    }

    /**
     * Result of waiting for a container.
     *
     * @param exitCode the container exit code
     * @param timedOut whether the container was killed due to timeout
     */
    public record WaitOutcome(int exitCode, boolean timedOut) {}
}
