package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.practices.PracticeJudgment;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds what a review submits for one practice under the holistic judgment: the answers that derive a chosen
 * outcome, each resting on the given citation.
 */
public final class AnsweredObservations {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private AnsweredObservations() {}

    /** The judgment every practice in these tests is reviewed with. */
    public static Map<String, PracticeJudgment> judgments(String... slugs) {
        var judgments = new java.util.HashMap<String, PracticeJudgment>();
        for (String slug : slugs) judgments.put(slug, PracticeJudgment.holistic());
        return judgments;
    }

    /** A citation of a staged record, quoting one line of it. */
    public static ObjectNode recordCitation(String artifactPath, int line, String quote) {
        ObjectNode citation = MAPPER.createObjectNode();
        citation.put("sourceKind", "scm.pull-request.core");
        citation.put("artifactPath", artifactPath);
        citation.put("path", artifactPath);
        citation.put("startLine", line);
        citation.put("endLine", line);
        citation.put("quote", quote);
        return citation;
    }

    /**
     * One practice's answers that the holistic judgment derives into {@code outcome} (and {@code severity}).
     *
     * @param citation the citation every answer rests on
     */
    public static ObjectNode observation(
            String slug, String summary, Outcome outcome, @Nullable Severity severity, JsonNode citation) {
        boolean occasion = outcome != Outcome.NOT_APPLICABLE;
        boolean shortfall = outcome == Outcome.NOT_MET;
        ObjectNode observation = MAPPER.createObjectNode();
        observation.put("practiceSlug", slug);
        observation.put("summary", summary);
        ArrayNode answers = observation.putArray("answers");
        answers.add(answer("has_occasion", occasion ? "YES" : "NO", citation));
        if (outcome == Outcome.UNDETERMINED) {
            ObjectNode open = answer("meets_standard", "UNDETERMINED", citation);
            open.put("wouldSettleIt", "the linked issue's body");
            answers.add(open);
        } else {
            answers.add(answer("meets_standard", shortfall ? "NO" : "YES", citation));
        }
        answers.add(answer("major_shortfall", shortfall && severity != Severity.MINOR ? "YES" : "NO", citation));
        answers.add(answer("critical_shortfall", severity == Severity.CRITICAL ? "YES" : "NO", citation));
        return observation;
    }

    public static ArrayNode submitted(ObjectNode... observations) {
        ArrayNode submitted = MAPPER.createArrayNode();
        for (ObjectNode observation : observations) submitted.add(observation);
        return submitted;
    }

    private static ObjectNode answer(String question, String value, JsonNode citation) {
        ObjectNode answer = MAPPER.createObjectNode();
        answer.put("question", question);
        answer.put("answer", value);
        answer.put("because", "The cited line shows it.");
        answer.putArray("citations").add(citation.deepCopy());
        return answer;
    }
}
