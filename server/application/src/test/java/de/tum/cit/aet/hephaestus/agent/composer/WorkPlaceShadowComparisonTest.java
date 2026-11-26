package de.tum.cit.aet.hephaestus.agent.composer;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.handler.FeedbackTextPolicy;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionInputs;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The shadow work-place composer against {@code REVIEW_COMPOSE} on the composer fixture set: the 72 labelled
 * composition cases, the replay cases and the golden scenarios. From the handoff alone it renders the legacy review on
 * the work, offers the legacy {@code check_package} as its only tool, and reaches the legacy verdict on every draft.
 * The intended differences are named: the shadow note, "not reached" for a practice whose only result is private, and a
 * link that only a private quote supplies.
 */
@Tag("unit")
class WorkPlaceShadowComparisonTest {
    private static final JsonMapper MAPPER = new JsonMapper();
    private static final Instant SEALED = Instant.parse("2026-11-26T12:00:00Z");

    @Test
    void shouldRenderTheLegacyReviewTurnFromTheHandoffForEveryEvalCase() throws Exception {
        var scenarios = CompositionEvalCases.load();
        assertThat(scenarios).hasSizeGreaterThan(50);
        for (var scenario : scenarios) {
            var session = WorkPlaceComposer.session(handoff(scenario), scenario.history());
            assertThat(session.system()).as(scenario.id()).isEqualTo(ComposerPrompts.reviewSystem());
            assertThat(session.input())
                    .as(scenario.id())
                    .isEqualTo(scenario.turn().text() + WorkPlaceComposer.SHADOW_NOTE);
            assertThat(session.tools()).as(scenario.id()).singleElement().satisfies(tool -> {
                assertThat(tool.name()).isEqualTo("check_package");
                assertThat(tool.parameters()).isEqualTo(scenario.turn().toolParameters());
            });
        }
    }

    @Test
    void shouldReachTheLegacyVerdictOnEveryCompositionOfEveryEvalCase() throws Exception {
        int compared = 0;
        for (var scenario : CompositionEvalCases.load()) {
            var session = WorkPlaceComposer.session(handoff(scenario), scenario.history());
            for (JsonNode composition : scenario.item().path("compositions")) {
                var review = scenario.toModel(composition.path("review"));
                var legacy = CompositionEvalCases.check(scenario, review);
                var shadow = WorkPlaceComposer.check(session, review);
                String label = scenario.id() + "/" + composition.path("id").asString();
                assertThat(shadow.draft() != null).as(label).isEqualTo(legacy.accepted());
                if (!legacy.accepted() && !legacy.errors().getFirst().startsWith("text: "))
                    assertThat(shadow.errors()).as(label).isEqualTo(legacy.errors());
                compared++;
            }
        }
        assertThat(compared).isGreaterThan(70);
    }

    static List<String> replays() throws IOException {
        return ComposerReplay.names();
    }

    @ParameterizedTest
    @MethodSource("replays")
    void shouldOpenTheLegacySessionAndReachItsVerdictOnEachReplayedDraft(String name) throws Exception {
        var replay = ComposerReplay.load(name);
        var basis = ComposerReplay.basis(replay);
        var server = new ComposerReplay.Server(basis);
        var identity = new ResultAdmissionService.AdmissionIdentity(
                server.job.getId(), server.job.getWorkspaceId(), server.job.getAttempt(), ComposerReplay.RUN);
        var next = server.sessions().next(server.job, identity, Set.of()).orElse(null);
        if (!(next instanceof CompositionSessions.Session legacy)
                || legacy.destination() != FeedbackDestination.ON_WORK) {
            // A review on the work published earlier opens no session, so there is nothing to compare: say why.
            assertThat(basis.reviewedEarlier()
                            || replay.path("earlierDecisions").has("ON_WORK"))
                    .as(name + " opens no review on the work, though nothing decided it earlier")
                    .isTrue();
            return;
        }
        JsonNode read = MAPPER.valueToTree(server.admission.publicFeedbackHistory(identity));
        var handoff = handoff(basis);
        var shadow = WorkPlaceComposer.session(handoff, read);
        assertThat(shadow.system()).isEqualTo(legacy.system());
        assertThat(shadow.input()).isEqualTo(legacy.input() + WorkPlaceComposer.SHADOW_NOTE);
        var check = legacy.tools().stream()
                .filter(tool -> tool.name().equals("check_package"))
                .findFirst()
                .orElseThrow();
        assertThat(shadow.tools()).singleElement().satisfies(tool -> {
            assertThat(tool.name()).isEqualTo(check.name());
            assertThat(tool.parameters()).isEqualTo(check.parameters());
        });
        var legacyTurn = legacyTurn(server, identity, read);
        var everyQuote = FeedbackTextPolicy.suppliedBy(quotes(basis.admission()));
        for (JsonNode arguments : onWorkChecks(replay)) {
            var parsed =
                    ReviewOnWork.read(arguments, legacyTurn.restable(), legacyTurn.lineNotes(), legacyTurn.witnesses());
            var verdict = WorkPlaceComposer.check(shadow, arguments);
            var composed = parsed.review();
            if (composed == null) {
                assertThat(verdict.draft()).as(name).isNull();
                assertThat(verdict.errors()).as(name).isEqualTo(parsed.errors());
                continue;
            }
            boolean refused = CompositionPackages.review(composed, null).composition().stream()
                    .anyMatch(piece -> everyQuote.refusal(piece) != null);
            assertThat(verdict.draft()).as(name).isEqualTo(refused ? null : composed);
        }
    }

    @Test
    void shouldDifferOnlyInNotReachedWhenAPracticeHasOnlyAPrivateResult() throws Exception {
        var basis = ComposerReplay.basis(MAPPER.readTree("""
                {"scenario": "oversized", "copyCapabilities": ["SUMMARY", "LINE_NOTE", "PRACTICE_PAGE", "CONVERSATION"]}
                """));
        var server = new ComposerReplay.Server(basis);
        var identity = new ResultAdmissionService.AdmissionIdentity(
                server.job.getId(), server.job.getWorkspaceId(), server.job.getAttempt(), ComposerReplay.RUN);
        JsonNode read = MAPPER.valueToTree(server.admission.publicFeedbackHistory(identity));
        var handoff = handoff(basis);
        var plans = plans(server);
        var plan = plans.found(server.job, identity);
        assertThat(plan).isNotNull();
        var legacyBasis = plans.basis(server.job, plan);
        assertThat(handoff.notReached())
                .as("the private-only practice is not reached for the public writer")
                .contains("describes-changes")
                .containsAll(legacyBasis.notReached());
        assertThat(legacyBasis.notReached()).doesNotContain("describes-changes");
        var rerendered = ComposerTurns.reviewTurn(
                legacyBasis.files(),
                legacyBasis.captured(),
                legacyBasis.folderIndex(),
                legacyBasis.practiceIndex(),
                plan.state(),
                plan.request(),
                read,
                handoff.notReached(),
                PrimarySource.captured(
                        legacyBasis.files(),
                        legacyBasis.folderIndex(),
                        ComposerFiles.CONTEXT_ROOT,
                        ComposerFiles.REPOSITORY_ROOT));
        assertThat(WorkPlaceComposer.session(handoff, read).input())
                .as("with the same not-reached list, the legacy turn is the shadow turn")
                .isEqualTo(rerendered.text() + WorkPlaceComposer.SHADOW_NOTE);
    }

    @Test
    void shouldRefuseHostileTextAndALinkThatOnlyAPrivateQuoteSupplies() throws Exception {
        var replay = ComposerReplay.load("pull-request-published");
        var basis = ComposerReplay.basis(replay);
        var admission = (ObjectNode) basis.admission().deepCopy();
        var hidden = (ObjectNode) admission.path("admitted").get(0).deepCopy();
        hidden.put("resultId", "30000000-0000-4000-8000-00000000000f");
        hidden.put("publicEligible", false);
        ((ArrayNode) hidden.path("problems")).removeAll();
        ((ObjectNode) hidden.path("evidence").path("citations").get(0))
                .put("quote", "See https://internal.example/run/7");
        ((ArrayNode) admission.path("admitted")).add(hidden);
        var shadowed = new ComposerReplay.Basis(
                basis.inputs(),
                admission,
                basis.history(),
                basis.capabilities(),
                basis.reviewedEarlier(),
                basis.earlierDecisions(),
                basis.reasoningEffort(),
                basis.maxOutputTokens());
        var server = new ComposerReplay.Server(shadowed);
        var identity = new ResultAdmissionService.AdmissionIdentity(
                server.job.getId(), server.job.getWorkspaceId(), server.job.getAttempt(), ComposerReplay.RUN);
        var session = WorkPlaceComposer.session(
                handoff(shadowed), MAPPER.valueToTree(server.admission.publicFeedbackHistory(identity)));
        var draft = (ObjectNode) onWorkChecks(replay).getFirst();
        assertThat(WorkPlaceComposer.check(session, draft).draft()).isNotNull();
        for (String hostile : List.of(
                "Ask @octocat why fetches retry.",
                "The description should say <b>why</b> fetches retry.",
                "![retry](https://example.com/retry.png) says why fetches retry.",
                "The description should say why fetches retry.‮",
                "See https://internal.example/run/7 for why fetches retry.")) {
            var copy = draft.deepCopy();
            ((ObjectNode) copy.path("summary")).put("body", hostile);
            assertThat(WorkPlaceComposer.check(session, copy).draft())
                    .as(hostile)
                    .isNull();
        }
        var linked = draft.deepCopy();
        ((ObjectNode) linked.path("summary")).put("body", "See https://internal.example/run/7 for why fetches retry.");
        var withLink = ReviewOnWork.read(
                        linked,
                        session.turn().restable(),
                        session.turn().lineNotes(),
                        session.turn().witnesses())
                .review();
        assertThat(withLink).isNotNull();
        assertThat(CompositionPackages.review(withLink, null).composition().stream()
                        .allMatch(piece ->
                                FeedbackTextPolicy.suppliedBy(quotes(admission)).refusal(piece) == null))
                .as("legacy supplies the link from the private quote; the work place never reads that quote")
                .isTrue();
    }

    // ---------------------------------------------------------------- fixtures

    private static WorkPlaceHandoff handoff(CompositionEvalCases.Scenario scenario) throws IOException {
        var forms = new ArrayList<String>();
        MAPPER.readTree(scenario.inputs().get(SandboxLayout.FEEDBACK_COMPOSITION_PATH))
                .path("onWorkCopyForms")
                .forEach(form -> forms.add(form.asString()));
        return handoff(scenario.inputs(), scenario.response(), forms);
    }

    private static WorkPlaceHandoff handoff(ComposerReplay.Basis basis) {
        byte[] request = FeedbackCompositionInputs.request(
                        ReviewKind.ITEM,
                        ReviewOrigin.REQUEST,
                        Set.of(FeedbackDestination.ON_WORK),
                        basis.capabilities(),
                        MAPPER)
                .orElseThrow();
        var forms = new ArrayList<String>();
        MAPPER.readTree(request).path("onWorkCopyForms").forEach(form -> forms.add(form.asString()));
        return handoff(basis.inputs(), basis.admission(), forms);
    }

    /** The scope of a fixture, built as the seal builds it: every practice of the index is assigned. */
    static WorkPlaceHandoff handoff(Map<String, byte[]> inputs, JsonNode admission, List<String> forms) {
        var scoped = WorkPlaceScope.of(sealed(inputs, admission, forms)).orElseThrow();
        return WorkPlaceScope.read(scoped.payload(), scoped.digest());
    }

    static WorkPlaceScope.Sealed sealed(Map<String, byte[]> inputs, JsonNode admission, List<String> forms) {
        var basis = new TreeMap<>(inputs);
        basis.remove(SandboxLayout.FEEDBACK_COMPOSITION_PATH);
        var results = new ArrayList<JsonNode>();
        admission.path("admitted").forEach(results::add);
        var captured = new ArrayList<String>();
        MAPPER.readTree(basis.get(SandboxLayout.MANIFEST_PATH))
                .path("sources")
                .forEach(source -> captured.add(source.path("kind").asString()));
        var assigned = new ArrayList<String>();
        byte[] practices = basis.get(ComposerFiles.PRACTICE_INDEX);
        if (practices != null)
            MAPPER.readTree(practices)
                    .forEach(practice -> assigned.add(practice.path("slug").asString()));
        return new WorkPlaceScope.Sealed(
                basis,
                results,
                Set.copyOf(captured),
                assigned,
                forms,
                new WorkPlaceScope.Copy(UUID.nameUUIDFromBytes("root".getBytes(StandardCharsets.UTF_8)), 0),
                Map.of(),
                SEALED,
                SEALED.plus(Duration.ofDays(1)));
    }

    private static CompositionPlans plans(ComposerReplay.Server server) {
        return new CompositionPlans(
                server.admission, server.continuation, server.assignments, server.items, MAPPER, server.trace);
    }

    private static ReviewCompositionTurn legacyTurn(
            ComposerReplay.Server server, ResultAdmissionService.AdmissionIdentity identity, JsonNode read) {
        var plans = plans(server);
        var plan = plans.found(server.job, identity);
        assertThat(plan).isNotNull();
        var basis = plans.basis(server.job, plan);
        return ComposerTurns.reviewTurn(
                basis.files(),
                basis.captured(),
                basis.folderIndex(),
                basis.practiceIndex(),
                plan.state(),
                plan.request(),
                read,
                basis.notReached(),
                PrimarySource.captured(
                        basis.files(), basis.folderIndex(), ComposerFiles.CONTEXT_ROOT, ComposerFiles.REPOSITORY_ROOT));
    }

    /** The quotes every admitted result and its problems cite: the links that legacy preparation allows. */
    private static List<String> quotes(JsonNode admission) {
        var quotes = new ArrayList<String>();
        for (JsonNode result : admission.path("admitted")) {
            result.path("evidence").path("citations").forEach(citation -> add(quotes, citation));
            result.path("problems")
                    .forEach(problem -> problem.path("citations").forEach(citation -> add(quotes, citation)));
        }
        return quotes;
    }

    private static void add(List<String> quotes, JsonNode citation) {
        if (citation.path("quote").isString()) quotes.add(citation.path("quote").asString());
    }

    /** The {@code check_package} arguments that a case's model sends in its review on the work, which ends at publish. */
    private static List<JsonNode> onWorkChecks(JsonNode replay) {
        var checks = new ArrayList<JsonNode>();
        for (JsonNode answer : replay.path("responses")) {
            for (var call : calls(answer)) {
                if (call.name().equals("publish_package")) return checks;
                if (call.name().equals("check_package")) checks.add(call.arguments());
            }
        }
        return checks;
    }

    private record Call(String name, JsonNode arguments) {}

    private static List<Call> calls(JsonNode answer) {
        if (answer.has("tool")) return List.of(new Call(answer.path("tool").asString(), answer.path("arguments")));
        var calls = new ArrayList<Call>();
        for (JsonNode call :
                answer.path("body").path("choices").path(0).path("message").path("tool_calls")) {
            @Nullable String arguments = call.path("function").path("arguments").asString(null);
            calls.add(new Call(
                    call.path("function").path("name").asString(),
                    arguments == null ? MAPPER.createObjectNode() : MAPPER.readTree(arguments)));
        }
        return calls;
    }
}
