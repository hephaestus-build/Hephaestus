package de.tum.cit.aet.hephaestus.practices;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.QuestionAnswer;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * How a practice is judged: yes/no questions a review answers from evidence, and ordered rules that turn the
 * answers into an outcome. The reviewer never states an outcome; {@link #derive} does.
 *
 * <p>The rules are total over every YES/NO combination and every rule can decide, so each set of definite answers
 * has exactly one outcome. An UNDETERMINED answer is resolved every way: the outcome stands only when every
 * resolution agrees, so an unsettled question never turns into a claim about the work.
 */
@Schema(
        description = "Questions a review answers and rules that decide the outcome from the answers",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record PracticeJudgment(
        @NonNull @NotNull @Valid @ArraySchema(minItems = 1, maxItems = MAX_QUESTIONS)
        List<PracticeQuestion> questions,

        @NonNull @NotNull @Valid @ArraySchema(minItems = 2, maxItems = MAX_RULES)
        List<PracticeRule> rules) {
    public static final int MAX_QUESTIONS = 8;
    public static final int MAX_RULES = 32;

    @JsonCreator
    public PracticeJudgment(
            @JsonProperty("questions") List<PracticeQuestion> questions,
            @JsonProperty("rules") List<PracticeRule> rules) {
        this.questions = List.copyOf(Objects.requireNonNull(questions, "questions"));
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        if (this.questions.isEmpty()) {
            throw new IllegalArgumentException("A judgment needs at least one question.");
        }
        if (this.questions.size() > MAX_QUESTIONS) {
            throw new IllegalArgumentException("A judgment may ask at most " + MAX_QUESTIONS + " questions.");
        }
        Set<String> keys = new HashSet<>();
        for (PracticeQuestion question : this.questions) {
            if (!keys.add(question.key())) {
                throw new IllegalArgumentException("Question key “" + question.key() + "” is used twice.");
            }
        }
        if (this.rules.size() < 2 || this.rules.size() > MAX_RULES) {
            throw new IllegalArgumentException("A judgment needs 2–" + MAX_RULES + " rules.");
        }
        Set<String> ids = new HashSet<>();
        for (PracticeRule rule : this.rules) {
            if (!ids.add(rule.id())) {
                throw new IllegalArgumentException("Rule id “" + rule.id() + "” is used twice.");
            }
            for (String key : rule.when().keySet()) {
                if (!keys.contains(key)) {
                    throw new IllegalArgumentException("Rule “" + rule.id() + "” refers to question “" + key
                            + "”, which this practice does not ask.");
                }
            }
        }
        if (!this.rules.getLast().when().isEmpty()) {
            throw new IllegalArgumentException(
                    "The last rule must have no conditions, so every combination of answers has an outcome.");
        }
        Set<String> reached = new HashSet<>();
        for (Map<String, QuestionAnswer> combination : combinations(keyList(), Map.of())) {
            reached.add(firstMatch(combination).id());
        }
        for (PracticeRule rule : this.rules) {
            if (!reached.contains(rule.id())) {
                throw new IllegalArgumentException("Rule “" + rule.id()
                        + "” can never decide: an earlier rule always matches first. Remove it or move it up.");
            }
        }
        for (PracticeQuestion question : this.questions) {
            if (this.rules.stream().noneMatch(rule -> rule.when().containsKey(question.key()))) {
                throw new IllegalArgumentException(
                        "Question “" + question.key() + "” is not used by any rule. Use it in a rule or remove it.");
            }
        }
    }

    /**
     * The holistic judgment: whether the occasion arises, whether the standard is met, and how severe a shortfall
     * is, each as the criteria define it. The starting point for a new practice, and what the judgment cutover gave
     * every practice that had criteria and no questions.
     */
    public static PracticeJudgment holistic() {
        return new PracticeJudgment(
                List.of(
                        new PracticeQuestion(
                                "has_occasion",
                                "The work gives this practice an occasion",
                                "Does this work give the practice something to review, as its criteria describe the"
                                        + " occasion? Answer NO only when a fact you can cite rules the practice out for this"
                                        + " work.",
                                "The work contains what the practice reviews.",
                                "A cited fact shows the practice has nothing to review in this work."),
                        new PracticeQuestion(
                                "meets_standard",
                                "The work meets the practice standard",
                                "Does the work meet the complete standard the criteria describe? One desirable"
                                        + " behaviour being present is not enough when the standard asks for more.",
                                "The cited evidence shows the complete standard is met.",
                                "The cited evidence shows a material shortfall against the standard."),
                        new PracticeQuestion(
                                "major_shortfall",
                                "The shortfall is major",
                                "Is the shortfall a real problem to fix before the work is done: major or above, where"
                                        + " the criteria grade severity? Answer NO when the work meets the standard.",
                                "The shortfall is a real problem to fix before the work is done.",
                                "There is no shortfall, or it is a bounded improvement."),
                        new PracticeQuestion(
                                "critical_shortfall",
                                "The shortfall is critical",
                                "Does the shortfall have a consequence that is expensive or impossible to undo, such as"
                                        + " a leaked credential or lost data? Answer NO when the work meets the standard or the"
                                        + " criteria never allow critical.",
                                "The shortfall has a consequence that is expensive or impossible to undo.",
                                "There is no shortfall, or its consequence can still be taken back.")),
                List.of(
                        new PracticeRule(
                                "no-occasion",
                                Map.of("has_occasion", QuestionAnswer.NO),
                                Outcome.NOT_APPLICABLE,
                                null,
                                "The occasion this practice reviews does not arise in this work."),
                        new PracticeRule(
                                "critical-shortfall",
                                orderedConditions("meets_standard", "critical_shortfall"),
                                Outcome.NOT_MET,
                                Severity.CRITICAL,
                                "The work falls short of the practice with a consequence that is hard to undo."),
                        new PracticeRule(
                                "major-shortfall",
                                orderedConditions("meets_standard", "major_shortfall"),
                                Outcome.NOT_MET,
                                Severity.MAJOR,
                                "The work falls short of the practice in a way to fix before it is done."),
                        new PracticeRule(
                                "shortfall",
                                Map.of("meets_standard", QuestionAnswer.NO),
                                Outcome.NOT_MET,
                                Severity.MINOR,
                                "The work falls short of the practice standard."),
                        new PracticeRule("met", Map.of(), Outcome.MET, null, "The work meets the practice standard.")));
    }

    private static Map<String, QuestionAnswer> orderedConditions(String shortfall, String escalation) {
        Map<String, QuestionAnswer> when = new LinkedHashMap<>();
        when.put(shortfall, QuestionAnswer.NO);
        when.put(escalation, QuestionAnswer.YES);
        return when;
    }

    /**
     * The outcome these answers decide.
     *
     * @param answers one answer per question, except a question {@link #skipConditions() skipped} by another answer
     *     or one that {@link #gradesSeverityOnly() only grades severity}, which is read as open; any other missing key,
     *     and an unknown one, is refused
     * @return the outcome, its severity, the deciding rule (absent when the outcome is UNDETERMINED because an open
     *     answer could change it) and the questions whose answers decide it
     */
    public Derivation derive(Map<String, QuestionAnswer> submitted) {
        Set<String> unknown = new HashSet<>(submitted.keySet());
        unknown.removeAll(keyList());
        Map<String, List<SkipCondition>> skips = skipConditions();
        Set<String> severityOnly = gradesSeverityOnly();
        List<String> missing = keyList().stream()
                .filter(key -> !submitted.containsKey(key) && !severityOnly.contains(key))
                .filter(key -> Objects.requireNonNull(skips.get(key)).stream()
                        .noneMatch(skip -> submitted.get(skip.question()) == skip.answer()))
                .toList();
        if (!unknown.isEmpty() || !missing.isEmpty()) {
            throw new IllegalArgumentException("Answers must cover this practice's questions"
                    + (missing.isEmpty() ? "" : "; missing " + missing)
                    + (unknown.isEmpty()
                            ? ""
                            : "; unknown " + unknown.stream().sorted().toList()));
        }
        // A skipped question cannot change the outcome or its severity, so it is resolved both ways like an open
        // answer and never decides.
        Map<String, QuestionAnswer> answers = new LinkedHashMap<>();
        keyList().forEach(key -> answers.put(key, submitted.getOrDefault(key, QuestionAnswer.UNDETERMINED)));
        List<String> open = keyList().stream()
                .filter(key -> answers.get(key) == QuestionAnswer.UNDETERMINED)
                .toList();
        Map<String, QuestionAnswer> definite = new LinkedHashMap<>(answers);
        open.forEach(definite::remove);
        List<Map<String, QuestionAnswer>> resolutions = combinations(open, definite);
        List<PracticeRule> decided = resolutions.stream().map(this::firstMatch).toList();
        if (decided.stream().map(PracticeRule::outcome).distinct().count() > 1) {
            List<String> material = open.stream()
                    .filter(key -> resolutions.stream().anyMatch(resolution -> {
                        Map<String, QuestionAnswer> flipped = new HashMap<>(resolution);
                        flipped.put(
                                key,
                                resolution.get(key) == QuestionAnswer.YES ? QuestionAnswer.NO : QuestionAnswer.YES);
                        return firstMatch(flipped).outcome()
                                != firstMatch(resolution).outcome();
                    }))
                    .toList();
            return new Derivation(Outcome.UNDETERMINED, null, null, material);
        }
        // Every resolution agrees on the outcome; claim only what holds in each. The severity is the least severe
        // of the deciding rules. A rule's reason heads the observation, so the rule named is one at that severity
        // whose conditions the definite answers alone satisfy: a reason one way of settling an open answer would
        // make false is not a fact about the work.
        PracticeRule least = decided.stream()
                .min(Comparator.comparingInt((PracticeRule candidate) -> severityRank(candidate.severity()))
                        .thenComparingInt(rules::indexOf))
                .orElseThrow();
        PracticeRule settled = decided.stream()
                .filter(candidate -> candidate.severity() == least.severity()
                        && definite.keySet().containsAll(candidate.when().keySet()))
                .min(Comparator.comparingInt(rules::indexOf))
                .orElse(null);
        if (settled != null) {
            return new Derivation(settled.outcome(), settled.severity(), settled, decidingKeys(settled, definite));
        }
        Set<String> conditioned = new HashSet<>();
        decided.forEach(candidate -> conditioned.addAll(candidate.when().keySet()));
        List<String> decisive = keyList().stream()
                .filter(key -> definite.containsKey(key) && conditioned.contains(key))
                .toList();
        return new Derivation(least.outcome(), least.severity(), null, decisive);
    }

    /**
     * When each question need not be answered: another question's answer under which every combination of the
     * remaining answers reaches the same outcome and severity whichever way this question is answered. Derived from
     * the rules, so a review asks only what can decide the outcome, and staged with the questions; the rules
     * themselves are not.
     *
     * @return for every question key, in question order, the answers that make it moot (empty when none do)
     */
    public Map<String, List<SkipCondition>> skipConditions() {
        Map<String, List<SkipCondition>> skips = new LinkedHashMap<>();
        for (String target : keyList()) {
            List<SkipCondition> conditions = new ArrayList<>();
            for (String gate : keyList()) {
                if (gate.equals(target)) continue;
                for (QuestionAnswer value : List.of(QuestionAnswer.YES, QuestionAnswer.NO)) {
                    if (mootWhen(target, gate, value)) conditions.add(new SkipCondition(gate, value));
                }
            }
            skips.put(target, List.copyOf(conditions));
        }
        return skips;
    }

    /**
     * The questions whose answer can change only the severity, never the outcome, under every combination of the other
     * answers. One left unanswered is read as open, so the result keeps the least severe band its answers allow: what
     * the authoring guide asks of a close call.
     */
    public Set<String> gradesSeverityOnly() {
        Set<String> severityOnly = new LinkedHashSet<>();
        for (String target : keyList()) {
            List<String> others =
                    keyList().stream().filter(key -> !key.equals(target)).toList();
            boolean outcomeFixed = combinations(others, Map.of()).stream().allMatch(combination -> {
                Map<String, QuestionAnswer> yes = new HashMap<>(combination);
                yes.put(target, QuestionAnswer.YES);
                Map<String, QuestionAnswer> no = new HashMap<>(combination);
                no.put(target, QuestionAnswer.NO);
                return firstMatch(yes).outcome() == firstMatch(no).outcome();
            });
            if (outcomeFixed) severityOnly.add(target);
        }
        return Collections.unmodifiableSet(severityOnly);
    }

    private boolean mootWhen(String target, String gate, QuestionAnswer value) {
        List<String> others = keyList().stream()
                .filter(key -> !key.equals(target) && !key.equals(gate))
                .toList();
        for (Map<String, QuestionAnswer> combination : combinations(others, Map.of(gate, value))) {
            Map<String, QuestionAnswer> yes = new HashMap<>(combination);
            yes.put(target, QuestionAnswer.YES);
            Map<String, QuestionAnswer> no = new HashMap<>(combination);
            no.put(target, QuestionAnswer.NO);
            PracticeRule whenYes = firstMatch(yes);
            PracticeRule whenNo = firstMatch(no);
            if (whenYes.outcome() != whenNo.outcome() || whenYes.severity() != whenNo.severity()) {
                return false;
            }
        }
        return true;
    }

    /**
     * A question's answer is not needed when {@code question} is answered {@code answer}.
     *
     * @param question the key of the deciding question
     * @param answer   YES or NO
     */
    public record SkipCondition(String question, QuestionAnswer answer) {}

    /**
     * The headline of an outcome every way of settling the open answers reaches, through different rules: no one
     * rule's reason holds in all of them, so the headline says only what does.
     */
    public static @Nullable String unsettledHeadline(Outcome outcome) {
        return switch (outcome) {
            case MET -> "The work meets the practice however the open questions are settled.";
            case NOT_MET -> "The work falls short of the practice however the open questions are settled.";
            case NOT_APPLICABLE ->
                "The practice has nothing to review in this work however the open questions are settled.";
            case UNDETERMINED -> null;
        };
    }

    public @Nullable PracticeQuestion question(String key) {
        return questions.stream()
                .filter(question -> question.key().equals(key))
                .findFirst()
                .orElse(null);
    }

    public @Nullable PracticeRule rule(String id) {
        return rules.stream().filter(rule -> rule.id().equals(id)).findFirst().orElse(null);
    }

    /**
     * The answers a rule decides from: its own conditions, or for the rule without conditions, the answers that ruled
     * out each rule before it, one per rule, in question order.
     */
    private List<String> decidingKeys(PracticeRule rule, Map<String, QuestionAnswer> definite) {
        if (!rule.when().isEmpty()) {
            return keyList().stream().filter(rule.when()::containsKey).toList();
        }
        Set<String> chosen = new HashSet<>();
        for (PracticeRule earlier : rules.subList(0, rules.indexOf(rule))) {
            List<String> ruledOutBy = keyList().stream()
                    .filter(key -> earlier.when().containsKey(key)
                            && definite.containsKey(key)
                            && definite.get(key) != earlier.when().get(key))
                    .toList();
            if (!ruledOutBy.isEmpty() && ruledOutBy.stream().noneMatch(chosen::contains)) {
                chosen.add(ruledOutBy.getFirst());
            }
        }
        return keyList().stream().filter(chosen::contains).toList();
    }

    private List<String> keyList() {
        return questions.stream().map(PracticeQuestion::key).toList();
    }

    private PracticeRule firstMatch(Map<String, QuestionAnswer> answers) {
        return rules.stream().filter(rule -> rule.matches(answers)).findFirst().orElseThrow();
    }

    private static int severityRank(@Nullable Severity severity) {
        return severity == null ? -1 : Severity.values().length - severity.ordinal();
    }

    private static List<Map<String, QuestionAnswer>> combinations(
            List<String> keys, Map<String, QuestionAnswer> fixed) {
        List<Map<String, QuestionAnswer>> result = new ArrayList<>();
        result.add(new LinkedHashMap<>(fixed));
        for (String key : keys) {
            List<Map<String, QuestionAnswer>> next = new ArrayList<>(result.size() * 2);
            for (Map<String, QuestionAnswer> partial : result) {
                for (QuestionAnswer answer : List.of(QuestionAnswer.YES, QuestionAnswer.NO)) {
                    Map<String, QuestionAnswer> extended = new LinkedHashMap<>(partial);
                    extended.put(key, answer);
                    next.add(extended);
                }
            }
            result = next;
        }
        return result;
    }

    /**
     * What a set of answers decides.
     *
     * @param outcome   the derived outcome
     * @param severity  present exactly for NOT_MET
     * @param rule      the deciding rule; absent when open answers leave the outcome UNDETERMINED, or when no rule
     *                  at the agreed severity holds whichever way they are settled
     * @param decisive  the questions whose answers decide the outcome, in question order
     */
    public record Derivation(
            Outcome outcome,
            @Nullable Severity severity,
            @Nullable PracticeRule rule,
            List<String> decisive) {
        public Derivation {
            decisive = List.copyOf(decisive);
        }
    }
}
