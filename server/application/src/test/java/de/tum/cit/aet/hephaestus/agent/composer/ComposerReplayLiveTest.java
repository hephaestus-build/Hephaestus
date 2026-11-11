package de.tum.cit.aet.hephaestus.agent.composer;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.LiveLlmCredentials;
import de.tum.cit.aet.hephaestus.testconfig.LiveLlmTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Measures the composer's noise floor with a live model, and records replay cases from it. Each scenario of the
 * scripted cases is composed {@code COMPOSITION_REPLAY_RUNS} times (default 3, at least 2) from the same sealed basis.
 * The report, written to {@code COMPOSITION_REPLAY_REPORT}, gives two shares of run pairs that decided alike. A run
 * pair agrees when both runs decided every destination alike and handed the attempt on with the same cause. A
 * destination pair agrees when both runs prepared or withheld the same pieces there, with the same reasons, results,
 * notes and summary presence, in any order; a pair where neither run produced the destination is not counted. The
 * words are not compared, because a live model never repeats them.
 *
 * <p>The first run of each scenario must also replay exactly from its recorded answers, which proves that a recorded
 * case decides an outcome by its answers alone. The report is written before that is asserted. With
 * {@code COMPOSITION_REPLAY_RECORD=1} the first run is written as the case {@code <scenario>-recorded}.
 * {@code COMPOSITION_REPLAY_BASES} names the scripted cases whose bases run, comma separated, so that a slow endpoint
 * runs one basis in each invocation of the live test task.
 */
@LiveLlmTest
class ComposerReplayLiveTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final List<String> BASES = List.of("pull-request-published", "issue-published", "resumed-private");
    private static final List<String> DESTINATIONS = List.of("ON_WORK", "PRACTICE_PAGE", "CONVERSATION");

    @Test
    void shouldMeasureHowOftenTwoLiveCompositionsOfOneBasisDecideAlike() throws Exception {
        String reportFile = Objects.requireNonNull(
                System.getenv("COMPOSITION_REPLAY_REPORT"), "COMPOSITION_REPLAY_REPORT names the report file");
        int runs = Integer.parseInt(Objects.requireNonNullElse(System.getenv("COMPOSITION_REPLAY_RUNS"), "3"));
        if (runs < 2) throw new IllegalArgumentException("COMPOSITION_REPLAY_RUNS must be at least 2");
        boolean record = "1".equals(System.getenv("COMPOSITION_REPLAY_RECORD"));
        var creds = LiveLlmCredentials.fromEnv();
        var transport = new LiveChatTransport(creds);

        var report = JSON.objectNode().put("model", creds.model()).put("runs", runs);
        var scenarios = report.putArray("scenarios");
        var runPairs = new Share();
        var destinationPairs = new Share();
        var notReplayed = new ArrayList<String>();
        String only = System.getenv("COMPOSITION_REPLAY_BASES");
        var bases = only == null ? BASES : List.of(only.split(","));
        for (String base : bases) {
            var basis = ComposerReplay.load(base);
            basis.remove("responses");
            basis.remove("expected");
            var structures = new ArrayList<JsonNode>();
            for (int run = 0; run < runs; run++) {
                var answers = new ArrayList<ModelSession.Answer>();
                var outcome = ComposerReplay.node(basis, live(transport, creds.model(), answers));
                structures.add(structure(outcome.packages(), outcome.handedOn()));
                if (run > 0) continue;
                var recorded = basis.deepCopy();
                recorded.put("description", "The live model's answers to " + base + "'s basis, recorded.");
                recorded.set("responses", ComposerReplay.recorded(answers));
                var replayed = ComposerReplay.node(recorded, ComposerReplay.replayed(recorded));
                if (!replayed.toJson().equals(outcome.toJson())) notReplayed.add(base);
                recorded.set("expected", outcome.toJson());
                if (record) ComposerReplay.write(recordedName(basis), recorded);
            }
            var entry =
                    scenarios.addObject().put("scenario", basis.path("scenario").asString());
            var scenarioRuns = new Share();
            for (int left = 0; left < runs; left++)
                for (int right = left + 1; right < runs; right++)
                    scenarioRuns.add(structures.get(left).equals(structures.get(right)));
            entry.set("runAgreement", scenarioRuns.toJson());
            runPairs.add(scenarioRuns);
            var destinations = entry.putObject("destinationAgreement");
            for (String destination : DESTINATIONS) {
                var share = new Share();
                for (int left = 0; left < runs; left++)
                    for (int right = left + 1; right < runs; right++) {
                        JsonNode one = structures.get(left).path(destination);
                        JsonNode other = structures.get(right).path(destination);
                        if (!one.isMissingNode() || !other.isMissingNode()) share.add(one.equals(other));
                    }
                destinations.set(destination, share.toJson());
                destinationPairs.add(share);
            }
            entry.set("structures", JSON.arrayNode().addAll(structures));
        }
        report.set("runAgreement", runPairs.toJson());
        report.set("destinationAgreement", destinationPairs.toJson());
        var unreplayed = report.putArray("notReplayed");
        notReplayed.forEach(unreplayed::add);
        var served = report.putArray("servedModels");
        transport.servedModels().stream().sorted().forEach(served::add);
        Files.writeString(
                Path.of(reportFile),
                ComposerReplay.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardCharsets.UTF_8);
        assertThat(notReplayed)
                .as("bases whose first run does not replay from its answers")
                .isEmpty();
    }

    /** How many of some pairs agreed: {@code null} as the share when there was no pair. */
    private static final class Share {
        private int agreeing;
        private int pairs;

        void add(boolean agrees) {
            pairs++;
            if (agrees) agreeing++;
        }

        void add(Share other) {
            agreeing += other.agreeing;
            pairs += other.pairs;
        }

        ObjectNode toJson() {
            var node = JSON.objectNode().put("agreeing", agreeing).put("pairs", pairs);
            if (pairs == 0) node.putNull("share");
            else node.put("share", (double) agreeing / pairs);
            return node;
        }
    }

    private static String recordedName(ObjectNode basis) {
        return basis.path("scenario").asString() + "-recorded";
    }

    /** Sends each call to the live model under its own name, and keeps every answer, a failed call's too. */
    private static Function<byte[], ModelSession.Answer> live(
            LiveChatTransport transport, String model, List<ModelSession.Answer> answers) {
        return body -> {
            var request = (ObjectNode) ComposerReplay.MAPPER.readTree(body);
            request.put("model", model);
            var sent = transport.call(ComposerReplay.MAPPER.writeValueAsBytes(request));
            var answer = new ModelSession.Answer(sent.status(), sent.body(), true, null);
            answers.add(answer);
            return answer;
        };
    }

    /**
     * What a run decided, by destination, without its words: each package's results, withholding reasons, quiet
     * decisions, note anchors and whether it has a summary.
     */
    static ObjectNode structure(JsonNode packages, @Nullable String handedOn) {
        var structure = JSON.objectNode();
        for (JsonNode accepted : packages)
            for (JsonNode piece : accepted.path("composition")) {
                String destination = piece.path("destination").asString();
                var decided = JSON.objectNode();
                decided.set("resultIds", sorted(piece.path("resultIds")));
                decided.set("withheldReason", piece.path("withheldReason"));
                decided.put("summary", piece.hasNonNull("summary"));
                var anchors = JSON.arrayNode();
                piece.path("notes")
                        .forEach(note -> anchors.add(note.path("problemId").asString() + "#"
                                + note.path("citationIndex").asInt()));
                decided.set("notes", sorted(anchors));
                var quiet = JSON.arrayNode();
                piece.path("trace")
                        .path("withheld")
                        .forEach(decision -> quiet.add(decision.path("reason").asString()));
                decided.set("quiet", sorted(quiet));
                decided.put("edit", accepted.hasNonNull("editOf"));
                structure.withArray(destination).add(decided);
            }
        // A destination's pieces decide alike in any order.
        structure.properties().forEach(entry -> {
            var pieces = new ArrayList<JsonNode>();
            entry.getValue().forEach(pieces::add);
            pieces.sort(Comparator.comparing(JsonNode::toString));
            entry.setValue(JSON.arrayNode().addAll(pieces));
        });
        if (handedOn != null) structure.put("HANDED_ON", handedOn);
        return structure;
    }

    private static ArrayNode sorted(JsonNode values) {
        var list = new ArrayList<String>();
        values.forEach(value -> list.add(value.asString()));
        list.sort(String::compareTo);
        var sorted = JSON.arrayNode();
        list.forEach(sorted::add);
        return sorted;
    }
}
