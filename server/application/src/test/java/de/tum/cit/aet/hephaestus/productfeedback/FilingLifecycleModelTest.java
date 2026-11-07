package de.tum.cit.aet.hephaestus.productfeedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.productfeedback.FilingLifecycle.Event;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A small model of one report and its world, searched breadth first through every reachable state. It is not a sample:
 * it visits all of them, with Pause, repositories that change, a new notice, lost write answers and erasure at every
 * point. Each step goes through the real {@link FilingLifecycle}, so a move the world needs and the table refuses fails
 * here with the path to it, and the search must take every move of the table.
 */
@Tag("unit")
class FilingLifecycleModelTest {

    private enum Fact {
        CHOSE_PUBLIC,
        ALLOW_PUBLIC,
        CONSENT_CURRENT,
        LATCH,
        DISCLOSED,
        PAUSED,
        PUBLIC_REPOSITORY,
        PRIVATE_REPOSITORY,
        /** A write may have left, so the next step reconciles. */
        ATTEMPTED,
        /** A write reached GitHub but its answer was lost. */
        UNCLEAR,
        ERASED
    }

    private record Move(FilingState from, Event event) {}

    private static final Set<Move> TAKEN = new HashSet<>();

    record World(
            FilingState state, @Nullable FilingRoute route, EnumSet<Fact> facts, int publicIssues, int privateIssues) {

        boolean has(Fact fact) {
            return facts.contains(fact);
        }

        World set(Fact fact, boolean on) {
            EnumSet<Fact> next = EnumSet.copyOf(facts);
            if (on) next.add(fact);
            else next.remove(fact);
            return new World(state, route, next, publicIssues, privateIssues);
        }

        World flip(Fact fact) {
            return set(fact, !has(fact));
        }

        World route(@Nullable FilingRoute next) {
            return new World(state, next, facts, publicIssues, privateIssues);
        }

        World issues(int nextPublic, int nextPrivate) {
            return new World(state, route, facts, nextPublic, nextPrivate);
        }

        World with(Event event) {
            FilingState to = FilingLifecycle.next(state, event)
                    .orElseThrow(() -> new AssertionError("The world needs " + event + " from " + state));
            TAKEN.add(new Move(state, event));
            return new World(to, route, facts, publicIssues, privateIssues);
        }

        World written() {
            World attempted = set(Fact.ATTEMPTED, true);
            return route == FilingRoute.PUBLIC
                    ? attempted.issues(publicIssues + 1, privateIssues)
                    : attempted.issues(publicIssues, privateIssues + 1);
        }

        boolean rests() {
            return state == FilingState.FILED || state == FilingState.SAVED || state == FilingState.STOPPED;
        }
    }

    private record Step(String name, World to) {}

    private static FilingRoute privateOrAdmins(World world) {
        return world.has(Fact.PRIVATE_REPOSITORY) ? FilingRoute.PRIVATE : FilingRoute.ADMINS;
    }

    /** Routing, as the gate decides it: public needs the person's current choice, a repository and no latch. */
    private static World routed(World world) {
        boolean goesPublic = world.has(Fact.ALLOW_PUBLIC)
                && world.has(Fact.CONSENT_CURRENT)
                && world.has(Fact.PUBLIC_REPOSITORY)
                && !world.has(Fact.LATCH);
        return goesPublic
                ? world.route(FilingRoute.PUBLIC).with(Event.ROUTED_TO_HOLD)
                : world.route(privateOrAdmins(world)).with(Event.ROUTED_TO_FILE);
    }

    private static World rerouted(World world) {
        World closed = world.set(Fact.ALLOW_PUBLIC, false);
        return world.has(Fact.PRIVATE_REPOSITORY)
                ? closed.route(FilingRoute.PRIVATE).with(Event.REROUTED_PRIVATE)
                : closed.route(FilingRoute.ADMINS).with(Event.SAVED);
    }

    /** What one filing claim can decide, in the order of {@code FilingService.claim}. */
    private static void claim(World w, List<Step> out) {
        boolean noDestination = !w.has(Fact.PUBLIC_REPOSITORY) && !w.has(Fact.PRIVATE_REPOSITORY);
        if (w.has(Fact.PAUSED) || noDestination || w.route() == FilingRoute.ADMINS) {
            out.add(new Step("kept for the admins", w.route(FilingRoute.ADMINS).with(Event.SAVED)));
            return;
        }
        boolean isPublic = w.route() == FilingRoute.PUBLIC;
        if (isPublic) {
            if (!w.has(Fact.ALLOW_PUBLIC) || !w.has(Fact.CONSENT_CURRENT)) {
                out.add(new Step("consent is no longer current", rerouted(w)));
                return;
            }
            out.add(new Step("daily public limit", rerouted(w)));
        }
        if (!w.has(isPublic ? Fact.PUBLIC_REPOSITORY : Fact.PRIVATE_REPOSITORY)) {
            out.add(new Step("destination changed", w.route(FilingRoute.ADMINS).with(Event.SAVED)));
            return;
        }
        out.add(new Step("repository unreachable", w.with(Event.STOPPED)));
        out.add(new Step("filing claims", w.with(Event.FILING_CLAIMED)));
    }

    private static void filing(World w, List<Step> out) {
        // A lease that ends takes the row over; a row that may have written is only ever reconciled.
        out.add(new Step("lease ends", w.with(Event.FILING_CLAIMED)));
        if (!w.has(Fact.ATTEMPTED)) {
            out.add(new Step("write succeeds", w.written().with(Event.FILED)));
            out.add(new Step("write answer is lost", w.written().set(Fact.UNCLEAR, true)));
            out.add(new Step("write is refused", w.set(Fact.ATTEMPTED, true).with(Event.STOPPED)));
            return;
        }
        out.add(new Step("lookups keep failing", w.with(Event.STOPPED)));
        // The lookup answers truly or throws: an unclear lookup counts as unknown, never as absent.
        if (w.has(Fact.UNCLEAR))
            out.add(new Step(
                    "marker finds the issue", w.set(Fact.UNCLEAR, false).with(Event.FILED)));
        else
            out.add(new Step(
                    "marker finds nothing", w.set(Fact.ATTEMPTED, false).with(Event.FILING_REQUEUED)));
    }

    private static void filed(World w, List<Step> out) {
        if (w.route() == FilingRoute.PUBLIC && w.has(Fact.PRIVATE_REPOSITORY)) {
            World moved = w.issues(w.publicIssues() - 1, w.privateIssues() + 1).set(Fact.ALLOW_PUBLIC, false);
            out.add(new Step("make private", moved.route(FilingRoute.PRIVATE).with(Event.ROUTE_CHANGED_ON_GITHUB)));
        }
        // What FollowService asks of a promotion: RoutingGate.refusePromotion, then a public repository.
        boolean promotable = w.has(Fact.ALLOW_PUBLIC)
                && w.has(Fact.CONSENT_CURRENT)
                && !w.has(Fact.LATCH)
                && w.has(Fact.PUBLIC_REPOSITORY);
        if (w.route() == FilingRoute.PRIVATE && promotable) {
            World moved = w.issues(w.publicIssues() + 1, w.privateIssues() - 1);
            out.add(new Step("publish", moved.route(FilingRoute.PUBLIC).with(Event.ROUTE_CHANGED_ON_GITHUB)));
        }
    }

    private static void person(World w, List<Step> out) {
        World closed = w.set(Fact.ALLOW_PUBLIC, false);
        if (w.state() == FilingState.HELD)
            out.add(new Step("keep private", closed.route(privateOrAdmins(w)).with(Event.KEPT_PRIVATE)));
        else out.add(new Step("withdraw the choice", closed));
        if (w.route() == FilingRoute.PRIVATE && w.has(Fact.PUBLIC_REPOSITORY)) {
            World ticked = w.set(Fact.CHOSE_PUBLIC, true).set(Fact.ALLOW_PUBLIC, true);
            out.add(new Step("allow public", ticked.set(Fact.CONSENT_CURRENT, true)));
        }
    }

    private static List<Step> steps(World w) {
        var out = new ArrayList<Step>();
        if (w.has(Fact.ERASED)) return out;
        out.add(new Step("erase", w.set(Fact.ERASED, true)));
        out.add(new Step("pause changes", w.flip(Fact.PAUSED)));
        out.add(new Step("public repository changes", w.flip(Fact.PUBLIC_REPOSITORY)));
        out.add(new Step("private repository changes", w.flip(Fact.PRIVATE_REPOSITORY)));
        out.add(new Step("notice changes", w.set(Fact.CONSENT_CURRENT, false)));
        person(w, out);
        switch (w.state()) {
            case DRAFT_QUEUED -> {
                out.add(new Step("worker claims", w.with(Event.DRAFT_CLAIMED)));
                out.add(new Step("no model can draft", w.with(Event.DRAFT_ENDED)));
                out.add(new Step("expires", routed(w)));
            }
            case DRAFTING -> {
                out.add(new Step("worker ends", w.with(Event.DRAFT_ENDED)));
                out.add(new Step(
                        "worker ends with a security draft",
                        w.set(Fact.LATCH, true).with(Event.DRAFT_ENDED)));
                out.add(new Step("worker is replaced", w.with(Event.DRAFT_CLAIMED)));
                out.add(new Step("draft call fails with attempts left", w.with(Event.DRAFT_RETRIED)));
                out.add(new Step("expires", routed(w)));
            }
            case ANSWERED -> out.add(new Step("routes", routed(w)));
            case HELD -> {
                claim(w, out);
                out.add(new Step("edit", w.with(Event.EDITED)));
            }
            case QUEUED -> claim(w, out);
            case FILING -> filing(w, out);
            case FILED -> filed(w, out);
            case SAVED -> {
                if (w.has(Fact.PRIVATE_REPOSITORY) && !w.has(Fact.PAUSED)) {
                    World moved = w.set(Fact.ALLOW_PUBLIC, false).route(null);
                    out.add(new Step(
                            "move", moved.with(w.has(Fact.DISCLOSED) ? Event.MOVED_DRAFTED : Event.MOVED_UNDRAFTED)));
                }
            }
            case STOPPED -> {
                // FilingService.retry keeps the first attempt after a requeue, so a clean row can reconcile as well.
                out.add(new Step("retry reconciles", w.set(Fact.ATTEMPTED, true).with(Event.RETRIED_RECONCILE)));
                if (!w.has(Fact.ATTEMPTED)) {
                    out.add(new Step("retry routes", w.route(null).with(Event.RETRIED_ROUTE)));
                    out.add(new Step("retry drafts", w.route(null).with(Event.RETRIED_DRAFT)));
                }
            }
        }
        return out;
    }

    private static List<World> starts() {
        var worlds = new ArrayList<World>();
        for (int bits = 0; bits < 32; bits++) {
            boolean chosePublic = (bits & 1) != 0;
            boolean publicRepository = (bits & 2) != 0;
            boolean privateRepository = (bits & 4) != 0;
            boolean paused = (bits & 8) != 0;
            boolean disclosed = (bits & 16) != 0;
            EnumSet<Fact> facts = EnumSet.noneOf(Fact.class);
            if (chosePublic) facts.addAll(List.of(Fact.CHOSE_PUBLIC, Fact.ALLOW_PUBLIC, Fact.CONSENT_CURRENT));
            if (publicRepository) facts.add(Fact.PUBLIC_REPOSITORY);
            if (privateRepository) facts.add(Fact.PRIVATE_REPOSITORY);
            if (paused) facts.add(Fact.PAUSED);
            if (disclosed) facts.add(Fact.DISCLOSED);
            boolean saved = paused || (!publicRepository && !privateRepository) || !disclosed;
            worlds.add(new World(
                    saved ? FilingState.SAVED : FilingState.DRAFT_QUEUED,
                    saved ? FilingRoute.ADMINS : null,
                    facts,
                    0,
                    0));
        }
        return worlds;
    }

    private static String path(World world, Map<World, Step> arrivedBy, Map<World, World> previous) {
        var names = new ArrayList<String>();
        for (World at = world; arrivedBy.containsKey(at); at = previous.get(at))
            names.addFirst(arrivedBy.get(at).name());
        return String.join(" -> ", names);
    }

    private static Set<Move> tableMoves() {
        var moves = new HashSet<Move>();
        for (FilingState state : FilingState.values())
            for (Event event : FilingLifecycle.eventsFrom(state)) moves.add(new Move(state, event));
        return moves;
    }

    @Test
    void shouldHoldEveryInvariantInEveryReachableState() {
        TAKEN.clear();
        Set<World> seen = new HashSet<>(starts());
        var open = new ArrayDeque<>(seen);
        Map<World, Step> arrivedBy = new HashMap<>();
        Map<World, World> previous = new HashMap<>();
        Map<World, List<World>> edges = new HashMap<>();
        while (!open.isEmpty()) {
            World world = open.poll();
            String via = path(world, arrivedBy, previous);
            // No private report is public: a public issue needs the person's own choice.
            if (world.publicIssues() > 0)
                assertThat(world.has(Fact.CHOSE_PUBLIC)).as(via).isTrue();
            // No duplicate issue: a report has at most one issue, wherever it is.
            assertThat(world.publicIssues() + world.privateIssues()).as(via).isBetween(0, 1);
            assertThat(world.publicIssues()).as(via).isNotNegative();
            assertThat(world.privateIssues()).as(via).isNotNegative();
            // A filed report has its issue.
            if (world.state() == FilingState.FILED)
                assertThat(world.publicIssues() + world.privateIssues()).as(via).isEqualTo(1);
            var targets = new ArrayList<World>();
            for (Step step : steps(world)) {
                targets.add(step.to());
                if (seen.add(step.to())) {
                    arrivedBy.put(step.to(), step);
                    previous.put(step.to(), world);
                    open.add(step.to());
                }
            }
            edges.put(world, targets);
            // Erasure wins: it is open from every state.
            if (!world.has(Fact.ERASED)) assertThat(targets).as(via).contains(world.set(Fact.ERASED, true));
        }
        // No report is lost: without erasure, every report can still come to rest with someone.
        Set<World> alive = new HashSet<>(seen);
        alive.removeIf(world -> world.has(Fact.ERASED));
        Set<World> rest = new HashSet<>();
        for (World world : alive) if (world.rests()) rest.add(world);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (World world : alive)
                if (!rest.contains(world)
                        && edges.getOrDefault(world, List.of()).stream().anyMatch(rest::contains)) {
                    rest.add(world);
                    grew = true;
                }
        }
        assertThat(rest).containsExactlyInAnyOrderElementsOf(alive);
        assertThat(TAKEN).as("Every move of the table is exercised").isEqualTo(tableMoves());
    }
}
