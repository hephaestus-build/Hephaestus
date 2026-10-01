package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The application container that creates interactive sandbox resources. Only the process that
 * created a mentor sandbox tracks it, in memory, and processes sharing a Docker daemon cannot see
 * each other's memory. So every interactive resource records its creator when it is created, and
 * automatic cleanup can wait until that creator is gone.
 *
 * <p>The creator is the container this process runs in, found by the id Docker gives it as its default
 * hostname; it is also the one container {@link SandboxNetworkManager} joins to a sandbox network. A
 * process that cannot be identified that way records no creator, joins no network, and what it creates
 * is never removed by automatic cleanup.
 */
public class SandboxCreator {

    private static final Logger log = LoggerFactory.getLogger(SandboxCreator.class);

    public enum Liveness {
        /** The recorded container is running in the same start it created the resource in. */
        RUNNING,
        /** The recorded container is gone, stopped, or has restarted since. */
        GONE,
        /** The daemon could not say. */
        UNREADABLE,
        /** The resource records no creator: it predates creator labels, or its creator ran outside Docker. */
        UNRECORDED
    }

    private static final Pattern DEFAULT_HOSTNAME = Pattern.compile("[0-9a-f]{12,64}");

    private final DockerInspectOperations containers;
    private final Supplier<@Nullable String> hostname;
    private volatile @Nullable Map<String, String> self;
    private volatile boolean reportedUnidentified;

    public SandboxCreator(DockerInspectOperations containers) {
        this(containers, () -> System.getenv("HOSTNAME"));
    }

    SandboxCreator(DockerInspectOperations containers, Supplier<@Nullable String> hostname) {
        this.containers = containers;
        this.hostname = hostname;
    }

    /** Labels naming this process's container, or none when it cannot be identified. */
    public Map<String, String> labels() {
        Map<String, String> known = self;
        if (known != null) {
            return known;
        }
        String host = hostname.get();
        if (host == null || !DEFAULT_HOSTNAME.matcher(host).matches()) {
            reportUnidentified(host == null ? "HOSTNAME is not set" : "HOSTNAME " + host + " is not a container id");
            return Map.of();
        }
        try {
            // Docker names a container's hostname after its id unless told otherwise; a name or a custom
            // hostname can be shared by several containers, so only the id prefix identifies this one.
            var own = containers
                    .inspectContainerIdentity(host)
                    .filter(container -> container.running()
                            && container.id().startsWith(host)
                            && host.equals(container.hostname()));
            if (own.isPresent()) {
                Map<String, String> labels = Map.of(
                        SandboxLabels.CREATOR_CONTAINER, own.get().id(),
                        SandboxLabels.CREATOR_STARTED_AT, own.get().startedAt());
                self = labels;
                return labels;
            }
            reportUnidentified("no running container has id " + host);
        } catch (RuntimeException e) {
            reportUnidentified("inspecting " + host + " failed: " + e.getMessage());
        }
        return Map.of();
    }

    private void reportUnidentified(String reason) {
        if (!reportedUnidentified) {
            reportedUnidentified = true;
            log.warn(
                    "Cannot identify this process's container ({}); mentor sandboxes it starts while this lasts "
                            + "record no creator, so automatic cleanup never removes them",
                    reason);
        }
    }

    public Liveness liveness(Map<String, String> labels) {
        String id = labels.get(SandboxLabels.CREATOR_CONTAINER);
        String startedAt = labels.get(SandboxLabels.CREATOR_STARTED_AT);
        if (id == null || id.isBlank() || startedAt == null || startedAt.isBlank()) {
            return Liveness.UNRECORDED;
        }
        try {
            return containers
                    .inspectContainerIdentity(id)
                    .map(container ->
                            container.running() && container.startedAt().equals(startedAt)
                                    ? Liveness.RUNNING
                                    : Liveness.GONE)
                    .orElse(Liveness.GONE);
        } catch (RuntimeException e) {
            log.debug("Could not inspect sandbox creator {}: {}", id, e.getMessage());
            return Liveness.UNREADABLE;
        }
    }

    /** Whether this process, in its current start, created the resource. */
    public boolean createdBySelf(Map<String, String> labels) {
        Map<String, String> own = labels();
        return !own.isEmpty() && labels.entrySet().containsAll(own.entrySet());
    }
}
