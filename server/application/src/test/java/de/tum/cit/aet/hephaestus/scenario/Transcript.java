package de.tum.cit.aet.hephaestus.scenario;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What a subject did in one run of a scenario, in order. Two runs on one cassette give equal transcripts. */
public final class Transcript {

    /** A conversation entry in the person's own conversation in a place. */
    public record Entry(Instant at, String runId, Reader to, String text, List<String> basis) {
        public Entry {
            basis = List.copyOf(basis);
        }
    }

    /** A durable outside effect that a run queued, with the person on whose authority it runs. */
    public record OutboxAction(
            String id,
            String runId,
            Instant queuedAt,
            Instant dueAt,
            Person onBehalfOf,
            Destination destination,
            String text,
            List<String> basis) {
        public OutboxAction {
            basis = List.copyOf(basis);
        }
    }

    /** An outbox action that reached its destination. */
    public record Effect(String actionId, Instant at, Destination destination, String text) {}

    /** A model request and its reply, keyed by the canonical request hash. */
    public record ModelCall(String runId, String requestHash, String reply) {}

    /** How a run ended. */
    public record RunEnd(String runId, Instant at, Run.Outcome outcome) {}

    private final List<Entry> entries = new ArrayList<>();
    private final List<OutboxAction> outboxActions = new ArrayList<>();
    private final List<Effect> effects = new ArrayList<>();
    private final List<ModelCall> modelCalls = new ArrayList<>();
    private final List<RunEnd> runEnds = new ArrayList<>();

    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public List<OutboxAction> outboxActions() {
        return Collections.unmodifiableList(outboxActions);
    }

    public List<Effect> effects() {
        return Collections.unmodifiableList(effects);
    }

    public List<ModelCall> modelCalls() {
        return Collections.unmodifiableList(modelCalls);
    }

    public List<RunEnd> runEnds() {
        return Collections.unmodifiableList(runEnds);
    }

    void add(Entry entry) {
        entries.add(entry);
    }

    void add(OutboxAction action) {
        outboxActions.add(action);
    }

    void add(Effect effect) {
        effects.add(effect);
    }

    void add(ModelCall call) {
        modelCalls.add(call);
    }

    void add(RunEnd end) {
        runEnds.add(end);
    }
}
