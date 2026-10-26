package de.tum.cit.aet.hephaestus.agent.composer;

import static de.tum.cit.aet.hephaestus.agent.composer.Js.listOf;
import static de.tum.cit.aet.hephaestus.agent.composer.Js.string;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a composed text rests on, as the server records it, and the admitted results it may name. Shared by the review
 * on the work and the private feedback.
 */
final class Evidence {
    private Evidence() {}

    /** One admitted result, or one problem of it. The reference always carries the result the id belongs to. */
    record Ref(String resultId, @Nullable String problemId) {
        ObjectNode json() {
            var node = JsonNodeFactory.instance.objectNode().put("resultId", resultId);
            if (problemId != null) node.put("problemId", problemId);
            return node;
        }
    }

    /** One problem of an admitted result, with the citations a note can sit on. */
    record Problem(String problemId, List<JsonNode> citations) {}

    /** The facts of an admitted result that a composed text is checked against. */
    record Result(
            String practiceSlug,
            boolean summaryOnly,
            @Nullable String outcome,
            List<Problem> problems) {
        static Result of(JsonNode result) {
            var problems = new ArrayList<Problem>();
            for (JsonNode problem : listOf(result.path("problems")))
                if (problem.isObject())
                    problems.add(new Problem(
                            text(problem.path("problemId")),
                            listOf(problem.path("citations")).stream()
                                    .filter(JsonNode::isObject)
                                    .toList()));
            return new Result(
                    text(result.path("practiceSlug")),
                    result.path("summaryOnly").isBoolean()
                            && result.path("summaryOnly").asBoolean(),
                    string(result.path("outcome")),
                    problems);
        }

        boolean notMet() {
            return "NOT_MET".equals(outcome);
        }
    }

    /** {@code String(value)} for an id or slug the server wrote. */
    static String text(JsonNode value) {
        if (value.isString()) return value.asString();
        if (value.isMissingNode()) return "undefined";
        return value.isNull() ? "null" : JsonText.stringify(value, 0);
    }

    record Named(Result result, Ref ref) {}

    static Map<String, Result> byId(List<? extends JsonNode> results) {
        var map = new LinkedHashMap<String, Result>();
        for (JsonNode result : results) map.put(text(result.path("resultId")), Result.of(result));
        return map;
    }

    static Map<String, Named> named(Map<String, Result> results) {
        var named = new LinkedHashMap<String, Named>();
        for (var entry : results.entrySet()) {
            named.put(entry.getKey(), new Named(entry.getValue(), new Ref(entry.getKey(), null)));
            for (var problem : entry.getValue().problems())
                named.put(
                        problem.problemId(), new Named(entry.getValue(), new Ref(entry.getKey(), problem.problemId())));
        }
        return named;
    }

    /** The references the ids name, each once, in the order first named. */
    static List<Ref> refs(List<String> ids, Map<String, Named> named) {
        var refs = new LinkedHashMap<String, Ref>();
        for (String id : ids) {
            Named entry = named.get(id);
            if (entry == null) throw new IllegalArgumentException(id + " names no admitted result or problem");
            refs.put(
                    entry.ref().resultId() + "\0"
                            + (entry.ref().problemId() == null
                                    ? ""
                                    : entry.ref().problemId()),
                    entry.ref());
        }
        return List.copyOf(refs.values());
    }

    /** Unevaluated practices support neither positive nor negative claims. */
    static String notReachedNote(List<String> notReached) {
        if (notReached.isEmpty()) return "";
        String subject = notReached.size() == 1 ? "one of its practices" : notReached.size() + " of its practices";
        return "\nThis review did not settle " + subject + ": " + String.join(", ", notReached)
                + ". Say nothing about them, for or against, and do not describe this review as complete.\n\n";
    }

    /** The distinct practices with a NOT_MET result, sorted as JavaScript sorts strings. */
    static List<String> notMetPractices(List<? extends JsonNode> results) {
        return results.stream()
                .filter(result -> "NOT_MET".equals(string(result.path("outcome"))))
                .map(result -> text(result.path("practiceSlug")))
                .collect(Collectors.toCollection(TreeSet::new))
                .stream()
                .toList();
    }

    /** Private feedback requires its practice's admitted NOT_MET result; history alone authorizes no claim. */
    static @Nullable String validateFeedbackEvidence(
            String primaryPractice, List<String> basedOn, Map<String, Result> results) {
        var named = named(results);
        String unknown = basedOn.stream()
                .filter(id -> !named.containsKey(id))
                .findFirst()
                .orElse(null);
        if (unknown != null) {
            var own = results.entrySet().stream()
                    .filter(entry -> entry.getValue().practiceSlug().equals(primaryPractice))
                    .map(entry -> entry.getKey()
                            + (entry.getValue().problems().isEmpty()
                                    ? ""
                                    : " (its problems: "
                                            + entry.getValue().problems().stream()
                                                    .map(Problem::problemId)
                                                    .collect(Collectors.joining(", "))
                                            + ")"))
                    .toList();
            String hint = own.isEmpty()
                    ? "no admitted result belongs to " + primaryPractice
                    : "the admitted result of " + primaryPractice + " is " + String.join(", ", own);
            return "Evidence '" + unknown
                    + "' does not name an admitted result or problem from this run (basedOn takes a result's `resultId` or a problem's `problemId`; "
                    + hint + "); skipped.";
        }
        if (basedOn.stream().noneMatch(id -> {
            var result = Objects.requireNonNull(named.get(id)).result();
            return result.practiceSlug().equals(primaryPractice) && result.notMet();
        }))
            return "At least one basedOn id must name the NOT_MET result of the primary practice '" + primaryPractice
                    + "' or one of its problems; skipped.";
        if (basedOn.stream()
                .anyMatch(id -> !Objects.requireNonNull(named.get(id)).result().notMet()))
            return "Every supporting result must be NOT_MET for private feedback; skipped.";
        return null;
    }
}
