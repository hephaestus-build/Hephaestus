package de.tum.cit.aet.hephaestus.agent.handler.candidate;

import static de.tum.cit.aet.hephaestus.agent.handler.candidate.CandidateLimits.CANDIDATE_BYTES;
import static de.tum.cit.aet.hephaestus.agent.handler.candidate.CandidateLimits.WARRANT_FIELD_CHARS;

import de.tum.cit.aet.hephaestus.integration.core.spi.CandidateRefusal;
import de.tum.cit.aet.hephaestus.practices.spi.ClaimBasis;
import de.tum.cit.aet.hephaestus.practices.spi.ClaimedCoverage;
import de.tum.cit.aet.hephaestus.practices.spi.DutyBasis;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceCitation;
import de.tum.cit.aet.hephaestus.practices.spi.HostCoverage;
import de.tum.cit.aet.hephaestus.practices.spi.InapplicabilityWarrant;
import de.tum.cit.aet.hephaestus.practices.spi.IntroducedBasis;
import de.tum.cit.aet.hephaestus.practices.spi.OpenQuestion;
import de.tum.cit.aet.hephaestus.practices.spi.ReadReceiptStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCoverage;
import de.tum.cit.aet.hephaestus.practices.spi.ResultExample;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.SearchWarrant;
import de.tum.cit.aet.hephaestus.practices.spi.StateBasis;
import de.tum.cit.aet.hephaestus.practices.spi.WorsenedBasis;
import jakarta.validation.Validator;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

public final class CandidateParser {
    private static final JsonMapper MAPPER = CandidateWireMapper.create();
    private static final ObjectReader READER = MAPPER.readerFor(CandidateDocument.class);
    private static final Validator VALIDATOR = CandidateValidation.create();

    private CandidateParser() {}

    /**
     * The candidate this value is, or why it is not one.
     *
     * @throws CandidateRefusedException with the code of the first rule the value breaks
     */
    public static Candidate parse(@Nullable JsonNode value) {
        if (value == null || !value.isObject()) {
            throw refuse(CandidateRefusal.MALFORMED_CANDIDATE, "the candidate is missing");
        }
        long bytes = javaScriptJsonBytes(value);
        if (bytes > CANDIDATE_BYTES) {
            throw refuse(
                    CandidateRefusal.MALFORMED_CANDIDATE,
                    "the candidate has " + bytes + " bytes; at most " + CANDIDATE_BYTES);
        }
        CandidateDocument sent;
        try {
            rejectExplicitNulls(value);
            sent = READER.readValue(value);
        } catch (JacksonException | IllegalArgumentException malformed) {
            throw new CandidateRefusedException(
                    CandidateRefusal.MALFORMED_CANDIDATE,
                    "The candidate does not match the result contract",
                    malformed);
        }
        var violations = VALIDATOR.validate(sent);
        if (!violations.isEmpty()) {
            var violation = violations.stream()
                    .min(Comparator.comparing(v -> v.getPropertyPath().toString()))
                    .orElseThrow();
            throw refuse(
                    CandidateRefusal.MALFORMED_CANDIDATE,
                    "The candidate exceeds its contract bounds: " + violation.getPropertyPath() + " ("
                            + violation
                                    .getConstraintDescriptor()
                                    .getAnnotation()
                                    .annotationType()
                                    .getSimpleName() + ")");
        }
        Candidate candidate = rules(sent);
        for (var citation : candidate.allCitations())
            if (citation.endLine() < citation.startLine())
                throw refuse(CandidateRefusal.MALFORMED_CANDIDATE, "Citation lines are reversed");
        if (candidate.coverage() instanceof HostCoverage host) {
            for (var receipt : host.receipts()) {
                var start = receipt.startLine();
                var end = receipt.endLine();
                if (start != null && end != null && end < start)
                    throw refuse(CandidateRefusal.MALFORMED_CANDIDATE, "Receipt lines are reversed");
                var pattern = receipt.pattern();
                if ((receipt.status() == ReadReceiptStatus.SEARCHED) != (pattern != null)
                        || (pattern != null && (pattern.isEmpty() || pattern.length() > WARRANT_FIELD_CHARS)))
                    throw refuse(CandidateRefusal.MALFORMED_CANDIDATE, "Only a search receipt names its pattern");
            }
        }
        return candidate;
    }

    /**
     * {@code Buffer.byteLength(JSON.stringify(value))}, the size {@code parseCandidate} bounds. Jackson writes other
     * bytes for the same tree: {@code 3.0} where JavaScript writes {@code 3}, and an escape where JavaScript writes the
     * UTF-8 of a surrogate pair.
     */
    static long javaScriptJsonBytes(JsonNode value) {
        if (value.isObject()) {
            long bytes = 2L + Math.max(0, value.size() - 1);
            for (Map.Entry<String, JsonNode> property : value.properties()) {
                bytes += javaScriptStringBytes(property.getKey()) + 1 + javaScriptJsonBytes(property.getValue());
            }
            return bytes;
        }
        if (value.isArray()) {
            long bytes = 2L + Math.max(0, value.size() - 1);
            for (JsonNode item : value) {
                bytes += javaScriptJsonBytes(item);
            }
            return bytes;
        }
        if (value.isString()) {
            return javaScriptStringBytes(value.asString());
        }
        if (value.isNumber()) {
            double number = value.asDouble();
            return number == Math.rint(number) && Math.abs(number) < 0x1p63
                    ? Long.toString((long) number).length()
                    : value.toString().length();
        }
        return value.toString().length();
    }

    private static long javaScriptStringBytes(String text) {
        long bytes = 2;
        int at = 0;
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c == '"' || c == '\\' || c == '\b' || c == '\f' || c == '\n' || c == '\r' || c == '\t') {
                bytes += 2;
            } else if (c < 0x20) {
                bytes += 6;
            } else if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c)
                    && at + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(at + 1))) {
                bytes += 4;
                at++;
            } else if (Character.isSurrogate(c)) {
                bytes += 6;
            } else {
                bytes += 3;
            }
            at++;
        }
        return bytes;
    }

    private static Candidate rules(CandidateDocument sent) {
        ResultOutcome outcome = sent.outcome();
        List<CandidateProblem> problems = sent.problems();
        List<EvidenceCitation> citations = sent.citations();
        ResultCoverage coverage = sent.coverage();
        SearchWarrant searchNode = sent.evidence().search();
        InapplicabilityWarrant inapplicabilityNode = sent.evidence().inapplicability();
        List<OpenQuestion> questions = sent.evidence().openQuestions();
        boolean listsProblems = problems != null && !problems.isEmpty();
        if ((outcome == ResultOutcome.NOT_MET) != listsProblems || (problems != null && problems.isEmpty())) {
            throw refuse(
                    CandidateRefusal.OUTCOME_PROBLEM_MISMATCH,
                    outcome == ResultOutcome.NOT_MET
                            ? "a NOT_MET result lists its problems"
                            : "a " + outcome + " result lists no problem");
        }
        boolean search = searchNode != null;
        boolean inapplicability = inapplicabilityNode != null;
        boolean undecidability = questions != null;
        boolean warrants = switch (outcome) {
            case MET -> !inapplicability && !undecidability;
            case NOT_MET -> !inapplicability;
            case DOES_NOT_APPLY -> inapplicability && !search && !undecidability;
            case COULD_NOT_DETERMINE -> !inapplicability && undecidability;
        };
        if (!warrants) {
            throw refuse(
                    CandidateRefusal.WARRANT_OUTCOME_MISMATCH,
                    switch (outcome) {
                        case MET -> "a MET result may carry a search warrant only";
                        case NOT_MET -> "a NOT_MET result may carry a search warrant and open questions only";
                        case DOES_NOT_APPLY -> "a DOES_NOT_APPLY result carries its inapplicability warrant only";
                        case COULD_NOT_DETERMINE ->
                            "a COULD_NOT_DETERMINE result carries its open questions, and a search warrant at most";
                    });
        }
        if (outcome != ResultOutcome.NOT_MET && citations.isEmpty()) {
            throw refuse(CandidateRefusal.RESULT_CITATIONS_REQUIRED, "a " + outcome + " result cites what it rests on");
        }
        boolean complete = switch (coverage) {
            case HostCoverage host -> host.complete();
            case ClaimedCoverage claimed -> claimed.complete();
        };
        if (outcome == ResultOutcome.MET && !complete) {
            throw refuse(CandidateRefusal.MET_COVERAGE_INCOMPLETE, "a MET result needs complete coverage");
        }
        String coverageProblem = coverageProblem(coverage);
        if (coverageProblem != null) {
            throw refuse(CandidateRefusal.COVERAGE_INCONSISTENT, coverageProblem);
        }
        ResultExample example = sent.example();
        if (example != null && outcome != ResultOutcome.MET && outcome != ResultOutcome.NOT_MET) {
            throw refuse(CandidateRefusal.EXAMPLE_OUTCOME_MISMATCH, "a " + outcome + " result has no example");
        }
        if (problems != null) {
            for (int index = 0; index < problems.size(); index++) {
                CandidateProblem problem = problems.get(index);
                for (int at : basisIndices(problem.basis())) {
                    if (at >= problem.citations().size()) {
                        throw refuse(
                                CandidateRefusal.BASIS_CITATION_OUT_OF_RANGE,
                                "problem " + index + ": its basis names citation " + at + ", and it has "
                                        + problem.citations().size());
                    }
                }
            }
            Set<String> keys = new HashSet<>();
            for (CandidateProblem problem : problems) {
                if (problem.key() != null && !keys.add(problem.key())) {
                    throw refuse(CandidateRefusal.DUPLICATE_PROBLEM_KEY, "two problems share a key");
                }
            }
        }
        List<OpenQuestion> openQuestions = questions == null ? List.of() : questions;
        return switch (outcome) {
            case MET -> new MetCandidate(sent.summary(), sent.explanation(), citations, coverage, example, searchNode);
            case NOT_MET ->
                new NotMetCandidate(
                        sent.summary(),
                        sent.explanation(),
                        citations,
                        coverage,
                        Objects.requireNonNull(problems),
                        example,
                        searchNode,
                        openQuestions);
            case DOES_NOT_APPLY ->
                new DoesNotApplyCandidate(
                        sent.summary(),
                        sent.explanation(),
                        citations,
                        coverage,
                        Objects.requireNonNull(inapplicabilityNode));
            case COULD_NOT_DETERMINE ->
                new CouldNotDetermineCandidate(
                        sent.summary(), sent.explanation(), citations, coverage, searchNode, openQuestions);
        };
    }

    private static @Nullable String coverageProblem(ResultCoverage coverage) {
        if (!(coverage instanceof HostCoverage host))
            return "coverage is what the host measured; a result cannot claim it";
        boolean complete = host.complete();
        boolean omits = host.omitted() != null && !host.omitted().isEmpty();
        if (complete && (host.truncatedReads() > 0 || host.failedReads() > 0))
            return "complete host coverage cannot have truncated or failed reads";
        return complete == omits
                ? (complete ? "complete coverage lists no omission" : "incomplete coverage lists what it omitted")
                : null;
    }

    private static List<Integer> basisIndices(ClaimBasis basis) {
        return switch (basis) {
            case IntroducedBasis introduced -> List.of(introduced.head());
            case WorsenedBasis worsened -> List.of(worsened.base(), worsened.head());
            case DutyBasis duty -> List.of(duty.event());
            case StateBasis state -> List.of();
        };
    }

    // Jackson creator binding represents both missing optionals and explicit null as Java null.
    // The wire contract permits JSON null only for claimed coverage's required total.
    private static void rejectExplicitNulls(JsonNode value) {
        if (value.isNull()) throw new IllegalArgumentException("Explicit null is not permitted");
        if (value.isObject()) {
            for (Map.Entry<String, JsonNode> property : value.properties()) {
                if (!property.getKey().equals("total") || !property.getValue().isNull())
                    rejectExplicitNulls(property.getValue());
            }
        } else if (value.isArray()) {
            for (JsonNode item : value) rejectExplicitNulls(item);
        }
    }

    private static CandidateRefusedException refuse(CandidateRefusal refusal, @Nullable String message) {
        return new CandidateRefusedException(refusal, message == null ? refusal.wireValue() : message);
    }
}
