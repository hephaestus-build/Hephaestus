package de.tum.cit.aet.hephaestus.scenario;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A minimal conversation that keeps the boundary rules, so the harness has a subject before the server has these
 * features. It keeps its own copy of the world, learned only from steps, and its own erasure journal outside its
 * backup.
 *
 * <p>A message from a member gets an answer from the records its sender may read there. It cancels the sender's
 * background run in that place. Erasing a record also erases each record derived from it. A Slack message goes to the workspace its Slack team is bound to. A finished review starts a background
 * run that writes to the author and queues a reminder, and a composer run that writes the copy on the work from team
 * records only. Each send rechecks the authority it was queued under.
 *
 * <p>Each {@link Fault} breaks one rule, so a test can show that the judge catches it.
 */
public final class PrototypeSubject implements ScenarioSubject {

    static final Duration MODEL_LATENCY = Duration.ofSeconds(30);
    static final Duration REMINDER_DELAY = Duration.ofHours(1);
    static final String FIRST_MODEL = "model-a";

    /** One broken rule. */
    public enum Fault {
        /** Puts every record it holds into each request, and neither reports nor cites any of these reads. */
        READS_EVERYTHING_UNREPORTED,
        /** Restores the backup and does not reapply the erasures made since. */
        RESTORE_WITHOUT_JOURNAL,
        /** Erases a record and keeps the records derived from it. */
        ERASURE_WITHOUT_CASCADE,
        /** Sends a queued effect without rechecking the authority it was queued under. */
        NO_RECHECK_AT_SEND,
        /** Keeps a person who left a workspace as a member. */
        IGNORES_LEAVE,
        /** Routes a Slack message to the workspace its sender wrote in last. */
        SLACK_TO_LAST_WORKSPACE,
        /** Keeps reading travelling records in a workspace after its AI provider changed. */
        IGNORES_PROVIDER_CHANGE,
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Set<Fault> faults;
    private final Map<Place, Set<Person>> members = new HashMap<>();
    private final Map<Reader, Integer> generations = new HashMap<>();
    private final Map<String, Place> slackTeams = new HashMap<>();
    private final Map<Place, String> models = new HashMap<>();
    private final Set<Place> pausedTransfers = new HashSet<>();
    private final Map<Person, Place> lastWorkspace = new HashMap<>();
    private final Map<Reader, Run> backgroundRuns = new HashMap<>();
    private final Map<String, Integer> stamps = new HashMap<>();
    private final Set<String> erasureJournal = new HashSet<>();
    private Map<String, ScenarioRecord> records = new LinkedHashMap<>();
    private Map<String, ScenarioRecord> backup = new LinkedHashMap<>();

    private PrototypeSubject(Set<Fault> faults) {
        this.faults = faults;
    }

    public static PrototypeSubject keepingTheRules() {
        return new PrototypeSubject(EnumSet.noneOf(Fault.class));
    }

    public static PrototypeSubject with(Fault fault) {
        return new PrototypeSubject(EnumSet.of(fault));
    }

    @Override
    public void accept(Step step, Harness harness) {
        switch (step) {
            case Step.Join join ->
                members.computeIfAbsent(join.place(), place -> new HashSet<>()).add(join.person());
            case Step.Leave leave -> {
                if (!faults.contains(Fault.IGNORES_LEAVE)) {
                    members.getOrDefault(leave.place(), new HashSet<>()).remove(leave.person());
                    generations.merge(new Reader(leave.person(), leave.place()), 1, Integer::sum);
                }
            }
            case Step.BindSlackTeam bind -> slackTeams.put(bind.slackTeam(), bind.place());
            case Step.ChangeProvider change -> {
                models.put(change.place(), change.model());
                if (!faults.contains(Fault.IGNORES_PROVIDER_CHANGE)) {
                    pausedTransfers.add(change.place());
                }
            }
            case Step.Plant plant -> records.put(plant.record().id(), plant.record());
            case Step.Erase erase -> erase(erase.recordId());
            case Step.Backup _ -> backup = new LinkedHashMap<>(records);
            case Step.Restore _ -> {
                records = new LinkedHashMap<>(backup);
                if (!faults.contains(Fault.RESTORE_WITHOUT_JOURNAL)) {
                    erasureJournal.forEach(records::remove);
                }
            }
            case Step.Message message -> {
                lastWorkspace.put(message.person(), message.place());
                Run background = backgroundRuns.remove(new Reader(message.person(), message.place()));
                if (background != null) {
                    harness.endRun(background, Run.Outcome.CANCELLED);
                }
                answer(harness, new Reader(message.person(), message.place()), message.text(), null);
            }
            case Step.SlackMessage slack -> {
                Place place = faults.contains(Fault.SLACK_TO_LAST_WORKSPACE)
                        ? lastWorkspace.getOrDefault(slack.person(), slackPlace(slack.slackTeam()))
                        : slackPlace(slack.slackTeam());
                answer(harness, new Reader(slack.person(), place), slack.text(), slack.slackTeam());
            }
            case Step.ReviewFinished review -> {
                background(harness, new Reader(review.author(), review.place()), review.work());
                compose(harness, review.author(), review.place(), review.work());
            }
        }
    }

    private void erase(String id) {
        records.remove(id);
        erasureJournal.add(id);
        if (!faults.contains(Fault.ERASURE_WITHOUT_CASCADE)) {
            records.values().stream()
                    .filter(record -> record.sources().contains(id))
                    .map(ScenarioRecord::id)
                    .toList()
                    .forEach(this::erase);
        }
    }

    private void answer(Harness harness, Reader asker, String text, @Nullable String slackTeam) {
        if (!isMember(asker)) {
            return;
        }
        Run run = harness.startRun(Run.Kind.INTERACTIVE, asker.place(), List.of(asker.person()));
        List<ScenarioRecord> read = read(harness, run, records(asker));
        String reply = harness.callModel(run, request(asker.place(), read, text));
        harness.after(MODEL_LATENCY, () -> {
            if (slackTeam == null) {
                harness.entry(run, asker, reply, ids(read));
            } else {
                Transcript.OutboxAction action = harness.queue(
                        run,
                        asker.person(),
                        new Destination.SlackDm(asker.person(), slackTeam),
                        Duration.ZERO,
                        reply,
                        ids(read));
                queue(harness, action, asker);
            }
            harness.endRun(run, Run.Outcome.ANSWERED);
        });
    }

    /** The background run: tells the author that the review finished, and reminds them later. */
    private void background(Harness harness, Reader author, String work) {
        Run run = harness.startRun(Run.Kind.BACKGROUND, author.place(), List.of(author.person()));
        backgroundRuns.put(author, run);
        List<ScenarioRecord> read = read(harness, run, records(author));
        String reply = harness.callModel(run, request(author.place(), read, "The review of " + work + " finished."));
        harness.after(MODEL_LATENCY, () -> {
            if (!backgroundRuns.remove(author, run)) {
                return;
            }
            harness.entry(run, author, reply, ids(read));
            Transcript.OutboxAction reminder = harness.queue(
                    run,
                    author.person(),
                    new Destination.Direct(author.person(), author.place()),
                    REMINDER_DELAY,
                    "Your feedback on " + work + " is waiting.",
                    List.of());
            queue(harness, reminder, author);
            harness.endRun(run, Run.Outcome.ANSWERED);
        });
    }

    /** The composer run: writes the copy on the work, which every member reads, from team records only. */
    private void compose(Harness harness, Person author, Place place, String work) {
        List<Person> readers = members.getOrDefault(place, Set.of()).stream()
                .sorted(Comparator.comparing(Person::name))
                .toList();
        Run run = harness.startRun(Run.Kind.COMPOSER, place, readers);
        List<ScenarioRecord> candidates = faults.contains(Fault.READS_EVERYTHING_UNREPORTED)
                ? List.copyOf(records.values())
                : records.values().stream()
                        .filter(record -> record.home().equals(place) && !record.ownerOnly())
                        .toList();
        List<ScenarioRecord> read = read(harness, run, candidates);
        String copy = harness.callModel(run, request(place, read, "Write the feedback on " + work + "."));
        harness.after(MODEL_LATENCY, () -> {
            Transcript.OutboxAction action =
                    harness.queue(run, author, new Destination.Work(place), Duration.ZERO, copy, ids(read));
            queue(harness, action, new Reader(author, place));
            harness.endRun(run, Run.Outcome.ANSWERED);
        });
    }

    /** Stamps the action with the authority it was queued under, and rechecks that authority when it is due. */
    private void queue(Harness harness, Transcript.OutboxAction action, Reader authority) {
        stamps.put(action.id(), generations.getOrDefault(authority, 0));
        harness.after(Duration.between(harness.now(), action.dueAt()), () -> {
            boolean unchanged = isMember(authority)
                    && Objects.equals(stamps.get(action.id()), generations.getOrDefault(authority, 0));
            if (unchanged || faults.contains(Fault.NO_RECHECK_AT_SEND)) {
                harness.send(action);
            }
        });
    }

    private List<ScenarioRecord> records(Reader reader) {
        if (faults.contains(Fault.READS_EVERYTHING_UNREPORTED)) {
            return List.copyOf(records.values());
        }
        if (!isMember(reader)) {
            return List.of();
        }
        return records.values().stream()
                .filter(record -> record.home().equals(reader.place())
                        || (record.travelsTo().contains(reader.place()) && !pausedTransfers.contains(reader.place())))
                .filter(record -> !record.ownerOnly() || record.owner().equals(reader.person()))
                .toList();
    }

    private List<ScenarioRecord> read(Harness harness, Run run, List<ScenarioRecord> read) {
        if (!faults.contains(Fault.READS_EVERYTHING_UNREPORTED)) {
            read.forEach(record -> harness.read(run, record.id()));
        }
        return read;
    }

    private String request(Place place, List<ScenarioRecord> read, String text) {
        ObjectNode request = JSON.createObjectNode();
        request.put("model", models.getOrDefault(place, FIRST_MODEL));
        ArrayNode messages = request.putArray("messages");
        List<String> context = new ArrayList<>();
        read.forEach(record -> context.add(record.content()));
        messages.addObject().put("role", "system").put("content", "Context: " + String.join(" ", context));
        messages.addObject().put("role", "user").put("content", text);
        return JSON.writeValueAsString(request);
    }

    private boolean isMember(Reader reader) {
        Person owner = reader.place().owner();
        return owner != null
                ? owner.equals(reader.person())
                : members.getOrDefault(reader.place(), Set.of()).contains(reader.person());
    }

    private Place slackPlace(String slackTeam) {
        Place place = slackTeams.get(slackTeam);
        if (place == null) {
            throw new IllegalStateException("No workspace is bound to Slack team " + slackTeam);
        }
        return place;
    }

    /** The basis that an output cites: the records its run read. */
    private List<String> ids(List<ScenarioRecord> records) {
        if (faults.contains(Fault.READS_EVERYTHING_UNREPORTED)) {
            return List.of();
        }
        return records.stream().map(ScenarioRecord::id).toList();
    }
}
