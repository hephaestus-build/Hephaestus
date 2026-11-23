package de.tum.cit.aet.hephaestus.scenario;

import de.tum.cit.aet.hephaestus.integration.core.sync.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Plays a scenario against a subject on a controlled clock, with no threads and no sleep.
 *
 * <p>Steps and the subject's own timers run in time order, and in the order scheduled at one time, so every run of a
 * scenario is the same. At one instant, the scenario's steps run before any timer that the subject set. The subject
 * acts only through the methods below. The judge checks each act as it happens, against the world at that time.
 */
public final class Harness {

    /** The transcript of a run and the judge's report on it. Every step's time is {@code start} plus its offset. */
    public record Result(Instant start, Transcript transcript, ScenarioReport report) {}

    private record Timer(Instant at, long sequence, Runnable action) {}

    private final MutableClock clock = MutableClock.atFixedInstant();
    private final Instant start = clock.instant();
    private final PriorityQueue<Timer> timers =
            new PriorityQueue<>(Comparator.comparing(Timer::at).thenComparingLong(Timer::sequence));
    private final Judge judge = new Judge();
    private final Transcript transcript = new Transcript();
    private final ScriptedModel model;
    private long sequence;
    private int runs;
    private int actions;

    private Harness(ScriptedModel model) {
        this.model = model;
    }

    public static Result run(Scenario scenario, ScenarioSubject subject, ScriptedModel model) {
        Harness harness = new Harness(model);
        for (Scenario.Timed timed : scenario.steps()) {
            harness.schedule(harness.start.plus(timed.offset()), () -> {
                harness.judge.apply(timed.step());
                subject.accept(timed.step(), harness);
            });
        }
        while (!harness.timers.isEmpty()) {
            Timer timer = harness.timers.remove();
            harness.clock.advance(Duration.between(harness.clock.instant(), timer.at()));
            timer.action().run();
        }
        return new Result(harness.start, harness.transcript, harness.judge.report());
    }

    public Instant now() {
        return clock.instant();
    }

    /** Runs {@code action} after {@code delay} on the scenario clock. */
    public void after(Duration delay, Runnable action) {
        if (delay.isNegative()) {
            throw new IllegalArgumentException("A timer cannot run in the past: " + delay);
        }
        schedule(now().plus(delay), action);
    }

    /** Opens a run that may read for {@code readers} in {@code place}. */
    public Run startRun(Run.Kind kind, Place place, List<Person> readers) {
        Set<Reader> audience = readers.stream()
                .map(person -> new Reader(person, place))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new Run("run-" + ++runs, kind, place, audience);
    }

    /** Reports that a run read a planted record. The judge judges the record it planted, not the subject's copy. */
    public void read(Run run, String recordId) {
        judge.checkRead(run, recordId);
    }

    /** Sends a request to the scenario's model. The request may hold only what every reader of the run may read. */
    public String callModel(Run run, String requestBody) {
        judge.checkModelRequest(run, requestBody);
        String reply = model.reply(requestBody);
        transcript.add(new Transcript.ModelCall(run.id(), CanonicalRequestHash.of(requestBody), reply));
        return reply;
    }

    /** Writes an entry to the person's own conversation in a place. */
    public void entry(Run run, Reader to, String text, List<String> basis) {
        judge.checkOutput(run.id(), "an entry of " + run.id() + " to " + to, text, basis, Set.of(to));
        transcript.add(new Transcript.Entry(now(), run.id(), to, text, basis));
    }

    /** Queues an effect of a run on the authority of {@code onBehalfOf}. The subject sends it with {@link #send}. */
    public Transcript.OutboxAction queue(
            Run run, Person onBehalfOf, Destination destination, Duration delay, String text, List<String> basis) {
        Transcript.OutboxAction action = new Transcript.OutboxAction(
                "action-" + ++actions, run.id(), now(), now().plus(delay), onBehalfOf, destination, text, basis);
        judge.queued(action);
        transcript.add(action);
        return action;
    }

    /** The effect reaches its destination now. */
    public void send(Transcript.OutboxAction action) {
        Set<Reader> readers = judge.audience(action.destination());
        judge.checkSend(action);
        judge.checkOutput(action.runId(), action.id() + " to " + readers, action.text(), action.basis(), readers);
        transcript.add(new Transcript.Effect(action.id(), now(), action.destination(), action.text()));
    }

    public void endRun(Run run, Run.Outcome outcome) {
        transcript.add(new Transcript.RunEnd(run.id(), now(), outcome));
    }

    private void schedule(Instant at, Runnable action) {
        timers.add(new Timer(at, sequence++, action));
    }
}
