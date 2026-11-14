package de.tum.cit.aet.hephaestus.agent.sandbox.claim;

import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * How a sandbox ended, as its event and its counter name it. The only place that classifies an end, so the reason set
 * stays closed.
 *
 * @param terminationReason the Kubernetes reason as Kubernetes names it, or {@code other} for a reason that is not one
 *     of {@link #KUBERNETES_REASONS}
 * @param oom whether the kernel killed the sandbox's container for its memory
 * @param errorType the type of the failure that left the Pod's status unread
 * @param httpStatus the HTTP status of the API's answer to that read, when an answer came
 */
record SandboxEnd(
        Reason reason,
        @Nullable String terminationReason,
        @Nullable Integer exitCode,
        @Nullable Integer restartCount,
        boolean oom,
        @Nullable String errorType,
        @Nullable Integer httpStatus) {

    enum Reason {
        COMPLETED,
        ERROR,
        OOM_KILLED,
        EVICTED,
        /** The container still ran, or stopped only because its Pod was being deleted, when the sandbox ended. */
        RUNNING,
        /** The Pod is gone, or its claim was gone before the server ended the sandbox. */
        DELETED,
        /** The server could not read the Pod's status. */
        UNREAD,
        /** Any other end. */
        OTHER;

        String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The Pod and container reasons that Kubernetes sets for an end; a record names no other reason as it is. */
    static final Set<String> KUBERNETES_REASONS = Set.of(
            "Completed",
            "ContainerCannotRun",
            "ContainerStatusUnknown",
            "DeadlineExceeded",
            "Error",
            "Evicted",
            "NodeLost",
            "NodeShutdown",
            "OOMKilled",
            "Shutdown",
            "StartError",
            "Terminated",
            "UnexpectedAdmissionError");

    static SandboxEnd deleted() {
        return new SandboxEnd(Reason.DELETED, null, null, null, false, null, null);
    }

    static SandboxEnd unread(RuntimeException failure) {
        if (failure instanceof SandboxClaims.PodReadException read)
            return new SandboxEnd(
                    Reason.UNREAD, null, null, null, false, read.errorType(), read.status() > 0 ? read.status() : null);
        return new SandboxEnd(
                Reason.UNREAD, null, null, null, false, failure.getClass().getName(), null);
    }

    /** The Pod's reason decides before its container's, since an evicted Pod's container ends with a generic reason. */
    static SandboxEnd of(SandboxClaims.PodEnd pod) {
        String raw = pod.podReason() != null ? pod.podReason() : pod.terminatedReason();
        String named = raw == null ? null : KUBERNETES_REASONS.contains(raw) ? raw : "other";
        return new SandboxEnd(
                reason(pod),
                named,
                pod.exitCode(),
                pod.restartCount(),
                "OOMKilled".equals(pod.terminatedReason()),
                null,
                null);
    }

    private static Reason reason(SandboxClaims.PodEnd pod) {
        if ("Evicted".equals(pod.podReason())) return Reason.EVICTED;
        String terminated = pod.terminatedReason();
        if (terminated == null)
            return pod.terminating() || "Running".equals(pod.phase()) ? Reason.RUNNING : Reason.OTHER;
        return switch (terminated) {
            case "OOMKilled" -> Reason.OOM_KILLED;
            case "Completed" -> Reason.COMPLETED;
            case "Error" -> Reason.ERROR;
            default -> Reason.OTHER;
        };
    }
}
