package de.tum.cit.aet.hephaestus.scenario;

import de.tum.cit.aet.hephaestus.scenario.ScenarioReport.Kind;
import de.tum.cit.aet.hephaestus.scenario.ScenarioReport.Violation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The harness's own view of who may read what. It applies every step before the subject sees it, and checks each
 * model request, read, entry and sent effect against that view. It judges by the records that the scenario planted,
 * never by the subject's copy of them.
 *
 * <p>A reader may read a record when the record is not erased, the reader is a member of the place, the record is at
 * home there or travels there while its transfer is not paused, an owner-only record is the reader's own, and the
 * reader may read each of its sources. A queued effect may be sent only while the person on whose authority it was
 * queued keeps the same authority in that place.
 *
 * <p>An output rests on everything its run read, and on every record whose canary reached the run's model. Each
 * reader of the output must be allowed to read all of it. So a model that paraphrases a record still cannot carry it
 * to a reader who may not read it.
 */
final class Judge {

    private final Map<Place, Set<Person>> members = new HashMap<>();
    private final Map<Reader, Integer> generations = new HashMap<>();
    private final Map<String, Place> slackTeams = new HashMap<>();
    private final Set<Place> pausedTransfers = new HashSet<>();
    private final Map<String, ScenarioRecord> records = new LinkedHashMap<>();
    private final Set<String> erased = new HashSet<>();
    private final Map<String, Set<String>> readByRun = new HashMap<>();
    private final Map<String, Set<String>> modelSawByRun = new HashMap<>();
    private final Map<String, Integer> stamps = new HashMap<>();
    private final List<Violation> violations = new ArrayList<>();
    private int attempts;

    void apply(Step step) {
        switch (step) {
            case Step.Join join ->
                members.computeIfAbsent(join.place(), place -> new HashSet<>()).add(join.person());
            case Step.Leave leave -> {
                Set<Person> people = members.get(leave.place());
                if (people != null) {
                    people.remove(leave.person());
                }
                generations.merge(new Reader(leave.person(), leave.place()), 1, Integer::sum);
            }
            case Step.BindSlackTeam bind -> slackTeams.put(bind.slackTeam(), bind.place());
            case Step.ChangeProvider change -> pausedTransfers.add(change.place());
            case Step.Plant plant -> records.put(plant.record().id(), plant.record());
            case Step.Erase erase -> erased.add(erase.recordId());
            case Step.Backup _, Step.Restore _, Step.Message _, Step.SlackMessage _, Step.ReviewFinished _ -> {}
        }
    }

    boolean mayRead(Reader reader, ScenarioRecord record) {
        boolean placed = record.home().equals(reader.place())
                || (record.travelsTo().contains(reader.place()) && !pausedTransfers.contains(reader.place()));
        return !erased.contains(record.id())
                && isMember(reader)
                && placed
                && (!record.ownerOnly() || record.owner().equals(reader.person()))
                && record.sources().stream().allMatch(source -> mayRead(reader, planted(source)));
    }

    boolean isMember(Reader reader) {
        Person owner = reader.place().owner();
        return owner != null
                ? owner.equals(reader.person())
                : members.getOrDefault(reader.place(), Set.of()).contains(reader.person());
    }

    /** The readers of a destination now. */
    Set<Reader> audience(Destination destination) {
        Set<Reader> readers = new LinkedHashSet<>();
        switch (destination) {
            case Destination.Work work -> {
                Person owner = work.place().owner();
                Set<Person> people = owner != null ? Set.of(owner) : members.getOrDefault(work.place(), Set.of());
                people.stream()
                        .sorted(Comparator.comparing(Person::name))
                        .forEach(person -> readers.add(new Reader(person, work.place())));
            }
            case Destination.Direct direct -> readers.add(new Reader(direct.person(), direct.place()));
            case Destination.SlackDm slack -> readers.add(new Reader(slack.person(), slackPlace(slack.slackTeam())));
        }
        return readers;
    }

    /** The place whose authority a destination needs. */
    Place place(Destination destination) {
        return switch (destination) {
            case Destination.Work work -> work.place();
            case Destination.Direct direct -> direct.place();
            case Destination.SlackDm slack -> slackPlace(slack.slackTeam());
        };
    }

    void checkRead(Run run, String recordId) {
        ScenarioRecord record = planted(recordId);
        readByRun.computeIfAbsent(run.id(), id -> new LinkedHashSet<>()).add(recordId);
        for (Reader reader : run.audience()) {
            if (!mayRead(reader, record)) {
                violations.add(
                        new Violation(Kind.UNAUTHORIZED_READ, run.id() + " read " + recordId + " for " + reader));
            }
        }
    }

    /** Checks a model request against the run's readers, and keeps each canary the model saw for the run's outputs. */
    void checkModelRequest(Run run, String request) {
        checkCanaries("the model request of " + run.id(), request, run.audience());
        Set<String> seen = modelSawByRun.computeIfAbsent(run.id(), id -> new LinkedHashSet<>());
        records.values().stream()
                .filter(record -> request.contains(record.canary()))
                .forEach(record -> seen.add(record.id()));
    }

    /** Checks an output of a run: its text, what it cites, and everything its run read or showed the model. */
    void checkOutput(String runId, String output, String text, List<String> basis, Set<Reader> readers) {
        checkCanaries(output, text, readers);
        for (String id : basis) {
            barred(planted(id), readers)
                    .forEach(reader -> violations.add(
                            new Violation(Kind.UNAUTHORIZED_READ, output + " cites " + id + " to " + reader)));
        }
        Set<String> modelSaw = modelSawByRun.getOrDefault(runId, Set.of());
        for (String id : modelSaw) {
            barred(planted(id), readers)
                    .forEach(reader -> violations.add(new Violation(
                            Kind.CANARY_LEAK,
                            "the canary of " + id + " reached the model of " + runId + ", and " + output + " reached "
                                    + reader)));
        }
        for (String id : readByRun.getOrDefault(runId, Set.of())) {
            if (!modelSaw.contains(id)) {
                barred(planted(id), readers)
                        .forEach(reader -> violations.add(new Violation(
                                Kind.UNAUTHORIZED_READ,
                                output + " rests on " + id + ", which " + reader + " may not read")));
            }
        }
    }

    /** Stamps an action with the authority generation of the person on whose behalf it was queued. */
    void queued(Transcript.OutboxAction action) {
        stamps.put(action.id(), generation(new Reader(action.onBehalfOf(), place(action.destination()))));
    }

    void checkSend(Transcript.OutboxAction action) {
        Reader authority = new Reader(action.onBehalfOf(), place(action.destination()));
        Integer stamp = stamps.get(action.id());
        if (!isMember(authority) || stamp == null || stamp != generation(authority)) {
            violations.add(new Violation(
                    Kind.UNAUTHORIZED_SEND,
                    action.id() + " was sent after the authority of " + authority + " changed since it was queued"));
        }
        audience(action.destination()).stream()
                .filter(reader -> !isMember(reader))
                .forEach(reader -> violations.add(new Violation(
                        Kind.UNAUTHORIZED_SEND, action.id() + " reached " + reader + ", who is not a member")));
    }

    ScenarioReport report() {
        return new ScenarioReport(violations, attempts);
    }

    /** Fails each canary in {@code content} whose record one of the readers may not read. */
    private void checkCanaries(String output, String content, Set<Reader> readers) {
        for (ScenarioRecord record : records.values()) {
            List<Reader> barred = barred(record, readers);
            if (barred.isEmpty()) {
                continue;
            }
            attempts++;
            if (content.contains(record.canary())) {
                barred.forEach(reader -> violations.add(new Violation(
                        Kind.CANARY_LEAK, "the canary of " + record.id() + " reached " + reader + " in " + output)));
            }
        }
    }

    private List<Reader> barred(ScenarioRecord record, Set<Reader> readers) {
        return readers.stream().filter(reader -> !mayRead(reader, record)).toList();
    }

    private ScenarioRecord planted(String id) {
        ScenarioRecord record = records.get(id);
        if (record == null) {
            throw new IllegalStateException("The scenario never planted record " + id);
        }
        return record;
    }

    private int generation(Reader reader) {
        return generations.getOrDefault(reader, 0);
    }

    private Place slackPlace(String slackTeam) {
        Place place = slackTeams.get(slackTeam);
        if (place == null) {
            throw new IllegalStateException("The scenario never bound Slack team " + slackTeam);
        }
        return place;
    }
}
