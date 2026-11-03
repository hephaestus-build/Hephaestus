package de.tum.cit.aet.hephaestus.practices.review.report;

import de.tum.cit.aet.hephaestus.practices.review.ReuseCandidateQuery;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedProblemSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedResultSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.Comparison;
import de.tum.cit.aet.hephaestus.practices.spi.HostCoverage;
import de.tum.cit.aet.hephaestus.practices.spi.ProblemCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class ProblemComparison {
    private final JsonMapper json;

    public ProblemComparison(JsonMapper json) {
        this.json = json;
    }

    public String identity(long practiceId, long itemId, ProblemCandidate problem, String ownPath, int ordinal) {
        if (problem.key() != null) return "key:" + problem.key();
        var primary = problem.citations().stream()
                .filter(c -> c.path().equals(ownPath))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("A keyless scan problem needs its own-file witness."));
        return "scheme-1:"
                + sha256(json.writeValueAsString(List.of(
                        1,
                        Long.toString(practiceId),
                        Long.toString(itemId),
                        primary.sourceKind().value(),
                        normalize(primary.quote()),
                        ordinal)));
    }

    public Map<String, AdmittedProblemSnapshot> identities(AdmittedResultSnapshot result, String ownPath) {
        var ordinals = new HashMap<String, Integer>();
        var keys = new LinkedHashMap<String, AdmittedProblemSnapshot>();
        for (var problem : result.problems()) {
            var own = problem.candidate().citations().stream()
                    .filter(c -> c.path().equals(ownPath))
                    .findFirst();
            if (problem.candidate().key() == null && own.isEmpty()) continue;
            var quote = own.isEmpty()
                    ? ""
                    : ProblemComparison.normalize(own.orElseThrow().quote());
            int ordinal = ordinals.merge(quote, 1, Integer::sum) - 1;
            keys.put(
                    identity(
                            result.candidate().practiceId(),
                            result.candidate().itemId(),
                            problem.candidate(),
                            ownPath,
                            ordinal),
                    problem);
        }
        return keys;
    }

    public static String normalize(String quote) {
        return Arrays.stream(quote.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1))
                .map(line -> line.replaceAll("^[ \\t]+|[ \\t]+$", ""))
                .collect(Collectors.joining("\n"));
    }

    public String content(ProblemCandidate problem) {
        var body = json.createObjectNode();
        body.put("severity", problem.severity().name());
        body.put("summary", problem.summary());
        body.put("explanation", problem.explanation());
        body.set("nextStep", json.valueToTree(problem.nextStep()));
        body.set("basis", json.valueToTree(problem.basis()));
        var citations = body.putArray("citations");
        for (var c : problem.citations()) {
            var citation = json.valueToTree(c).deepCopy();
            if (citation instanceof ObjectNode object) {
                object.remove("startLine");
                object.remove("endLine");
                object.remove("verification");
            }
            citations.add(citation);
        }
        return sha256(json.writeValueAsString(canonical(body)));
    }

    public JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            Map<String, JsonNode> fields = new TreeMap<>();
            value.properties().forEach(e -> fields.put(e.getKey(), canonical(e.getValue())));
            var object = json.createObjectNode();
            fields.forEach(object::set);
            return object;
        }
        if (value.isArray()) {
            var array = json.createArrayNode();
            value.forEach(v -> array.add(canonical(v)));
            return array;
        }
        return value;
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public static Comparison comparability(
            @Nullable Standard previous, Standard current, boolean previousListingComplete) {
        if (previous == null) return Comparison.FIRST_SCAN;
        if (!previous.selectorDigest().equals(current.selectorDigest())
                || !previous.reviewDigest().equals(current.reviewDigest())) return Comparison.STANDARD_CHANGED;
        if (previous.epoch() != current.epoch() || !previous.modelDigest().equals(current.modelDigest()))
            return Comparison.METHOD_CHANGED;
        return previousListingComplete ? Comparison.COMPARABLE : Comparison.INCOMPLETE;
    }

    public static boolean provesAbsence(
            ResultCandidate previous,
            ResultCandidate current,
            boolean comparable,
            boolean judged,
            boolean eitherMarked,
            boolean bothPermitted) {
        return comparable
                && judged
                && !eitherMarked
                && bothPermitted
                && !previous.itemVersion().equals(current.itemVersion())
                && previous.itemId() == current.itemId()
                && previous.practiceId() == current.practiceId()
                && (current.outcome() == ResultOutcome.MET || current.outcome() == ResultOutcome.NOT_MET)
                && current.coverage() instanceof HostCoverage host
                && ReuseCandidateQuery.trusted(host);
    }

    public record Standard(String selectorDigest, String reviewDigest, String modelDigest, int epoch) {}
}
