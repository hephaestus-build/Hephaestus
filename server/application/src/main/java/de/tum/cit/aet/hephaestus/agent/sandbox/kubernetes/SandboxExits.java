package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * Why a sandbox Pod ended, read just before it is deleted, because a deleted Pod takes its exit code and log with it.
 * Each removal counts in {@code sandbox.exits} by kind and reason; an exit that is not {@code completed} also logs one
 * {@code sandbox.exited} event. For a review, the event carries the runtime's own last diagnostic lines, which hold
 * the event names, counts, flags and closed codes of {@link DiagnosticVocabulary} only, and the number of other lines
 * that it left out.
 */
public class SandboxExits {

    private static final Logger log = LoggerFactory.getLogger(SandboxExits.class);

    /** What the sandbox ran. */
    public enum Kind {
        REVIEW,
        HOST,
        TURN,
        PROBE;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Why it ended; a closed set, so the metric and the alert stay bounded. */
    enum Reason {
        COMPLETED,
        ERROR,
        OOM_KILLED,
        DEADLINE_EXCEEDED,
        EVICTED,
        DISRUPTED,
        START_FAILED,
        NOT_FOUND,
        UNKNOWN;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A container waits for one of these and does not start on its own. */
    private static final Set<String> START_FAILURES = Set.of(
            "ErrImagePull",
            "ImagePullBackOff",
            "ErrImageNeverPull",
            "InvalidImageName",
            "CreateContainerConfigError",
            "CreateContainerError");

    /** The lines of a review's log that the worker keeps; any other line is dropped and only counted. */
    private static final DiagnosticVocabulary DIAGNOSTICS = DiagnosticVocabulary.load();

    private static final Pattern DETAIL = Pattern.compile("[A-Za-z]{1,64}");

    static final int TAIL_LINES_READ = 200;
    static final int TAIL_BYTES_READ = 65_536;
    static final int TAIL_LINES_KEPT = 20;
    static final int TAIL_LINE_CHARS = 300;
    static final int TAIL_CHARS = 4096;

    private final KubernetesPods pods;
    private final Map<Kind, Map<Reason, Counter>> exits = new EnumMap<>(Kind.class);

    public SandboxExits(KubernetesPods pods, MeterRegistry meters) {
        this.pods = pods;
        // Every pair exists from the start, so that a rate over it begins at zero.
        for (Kind kind : Kind.values()) {
            Map<Reason, Counter> byReason = new EnumMap<>(Reason.class);
            for (Reason reason : Reason.values())
                byReason.put(
                        reason,
                        Counter.builder(AgentMetrics.SANDBOX_EXITS)
                                .description("Sandbox Pods removed, by what they ran and why they ended")
                                .tag("kind", kind.tag())
                                .tag("reason", reason.tag())
                                .register(meters));
            exits.put(kind, byReason);
        }
    }

    /** Records how the Pod {@code name} ended. Call it before the Pod is deleted; it never fails the removal. */
    public void record(Kind kind, String name) {
        try {
            Pod pod = pods.get(name);
            report(kind, name, pod == null ? new Exit(Reason.NOT_FOUND, null, null, null) : exit(pod));
        } catch (RuntimeException e) {
            // The class only: the API's message can quote the request. The removal goes on.
            log.debug(
                    "Could not record how sandbox {} ended: {}",
                    name,
                    e.getClass().getSimpleName());
            try {
                count(kind, Reason.UNKNOWN);
            } catch (RuntimeException ignored) {
                // A meter registry that refuses is no reason to keep a sandbox.
            }
        }
    }

    private void report(Kind kind, String name, Exit exit) {
        count(kind, exit.reason());
        if (exit.reason() == Reason.COMPLETED) {
            return;
        }
        LoggingEventBuilder event = log.atWarn()
                .addKeyValue("event.name", "sandbox.exited")
                .addKeyValue("sandbox.kind", kind.tag())
                .addKeyValue("sandbox.exit.reason", exit.reason().tag());
        // A key that the MDC already holds would make the structured writer drop the whole line.
        if (MDC.get("sandbox.id") == null) event = event.addKeyValue("sandbox.id", name);
        String container = exit.container();
        if (container != null) {
            event = event.addKeyValue("container.name", container);
            // Only a review's runtime lines. A Heph host's output can hold the conversation, a provider's answer or a
            // credential, and the worker never copies it (docs/admin/runtime-roles.mdx); a turn sandbox runs the
            // model's commands; a container that never started wrote nothing.
            if (kind == Kind.REVIEW && exit.reason() != Reason.START_FAILED) {
                Tail tail = stderrTail(pods.tail(name, container, TAIL_LINES_READ, TAIL_BYTES_READ));
                if (!tail.text().isEmpty()) event = event.addKeyValue("sandbox.stderr.tail", tail.text());
                if (tail.dropped() > 0) event = event.addKeyValue("sandbox.stderr.dropped", tail.dropped());
            }
        }
        Integer code = exit.exitCode();
        if (code != null) event = event.addKeyValue("process.exit.code", code);
        String detail = exit.detail();
        if (detail != null && DETAIL.matcher(detail).matches())
            event = event.addKeyValue("sandbox.exit.detail", detail);
        event.log("A sandbox ended: {}", exit.reason().tag());
    }

    private void count(Kind kind, Reason reason) {
        Objects.requireNonNull(Objects.requireNonNull(exits.get(kind)).get(reason))
                .increment();
    }

    record Exit(
            Reason reason,
            @Nullable String container,
            @Nullable Integer exitCode,
            @Nullable String detail) {}

    /** The first cause that explains the end, from the Pod as a whole to one container. */
    static Exit exit(Pod pod) {
        var status = pod.getStatus();
        String podReason = status == null ? null : status.getReason();
        if ("Evicted".equals(podReason)) return new Exit(Reason.EVICTED, null, null, podReason);
        if ("DeadlineExceeded".equals(podReason)) return new Exit(Reason.DEADLINE_EXCEEDED, null, null, podReason);
        List<PodCondition> conditions =
                status == null || status.getConditions() == null ? List.of() : status.getConditions();
        if (conditions.stream().anyMatch(c -> "DisruptionTarget".equals(c.getType()) && "True".equals(c.getStatus())))
            return new Exit(Reason.DISRUPTED, null, null, "DisruptionTarget");
        List<ContainerStatus> containers = status == null
                ? List.of()
                : Stream.concat(
                                listOf(status.getInitContainerStatuses()).stream(),
                                listOf(status.getContainerStatuses()).stream())
                        .toList();
        for (ContainerStatus container : containers) {
            var state = container.getState();
            if (state != null
                    && state.getWaiting() != null
                    && START_FAILURES.contains(state.getWaiting().getReason()))
                return new Exit(
                        Reason.START_FAILED,
                        container.getName(),
                        null,
                        state.getWaiting().getReason());
        }
        for (ContainerStatus container : containers) {
            ContainerStateTerminated ended = terminated(container);
            if (ended == null) continue;
            if ("OOMKilled".equals(ended.getReason()))
                return new Exit(Reason.OOM_KILLED, container.getName(), ended.getExitCode(), ended.getReason());
            Integer code = ended.getExitCode();
            if (code != null && code != 0) return new Exit(Reason.ERROR, container.getName(), code, ended.getReason());
        }
        String phase = status == null ? null : status.getPhase();
        // A Pod that never ran a container did not start; a failed Pod without a failed container failed for a cause
        // that it does not name.
        if (phase == null || "Pending".equals(phase)) return new Exit(Reason.START_FAILED, null, null, null);
        if ("Failed".equals(phase) || "Unknown".equals(phase)) return new Exit(Reason.UNKNOWN, null, null, podReason);
        // Every container that ended ended well. One that still runs is ended by its worker, as it should be.
        return new Exit(Reason.COMPLETED, null, null, null);
    }

    /** The last diagnostic lines of a sandbox log, and how many other lines it left out. */
    record Tail(String text, int dropped) {}

    /**
     * The runtime's own last diagnostic lines: at most {@link #TAIL_LINES_KEPT}, each cut, all within {@link
     * #TAIL_CHARS}. A line that the {@link DiagnosticVocabulary} does not allow only counts.
     */
    static Tail stderrTail(String log) {
        List<String> kept = new ArrayList<>();
        int dropped = 0;
        for (String line : log.lines().toList()) {
            if (DIAGNOSTICS.keeps(line))
                kept.add(line.length() > TAIL_LINE_CHARS ? line.substring(0, TAIL_LINE_CHARS) : line);
            else if (!line.isBlank()) dropped++;
        }
        List<String> last = kept.subList(Math.max(0, kept.size() - TAIL_LINES_KEPT), kept.size());
        StringBuilder tail = new StringBuilder();
        for (int i = last.size() - 1; i >= 0; i--) {
            String line = last.get(i);
            if (tail.length() + line.length() + 1 > TAIL_CHARS) break;
            tail.insert(0, tail.isEmpty() ? line : line + "\n");
        }
        return new Tail(tail.toString(), dropped);
    }

    private static @Nullable ContainerStateTerminated terminated(ContainerStatus container) {
        var state = container.getState();
        if (state != null && state.getTerminated() != null) return state.getTerminated();
        var last = container.getLastState();
        return last == null ? null : last.getTerminated();
    }

    private static <T> List<T> listOf(@Nullable List<T> list) {
        return list == null ? List.of() : list;
    }
}
