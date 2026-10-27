package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.handler.candidate.Candidate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Words that say the repository, beyond the lines a result cites, lacks something: that nothing elsewhere handles a
 * failure, or that the code base defines no such thing. Only a search of the whole checkout can show that (ADR 0064 §
 * Absence). This is a short list of markers, not a reading of the prose. It catches the usual wording of such a claim
 * and leaves claims bounded to the change or the cited lines alone. A clause that states what the review did not
 * establish is a limit of the review, not a claim. Clauses end at a full stop, a semicolon, or a comma before a
 * conjunction, so such a limit cannot hide the claim beside it. {@code pi-session-reads.ts} holds the same markers, so the runner
 * asks the session once before admission withholds the result.
 */
final class AbsenceClaims {

    static final List<String> MARKERS = List.of(
            "\\b(?:no|none|nothing|not any|nowhere|never|lacks?|without|missing|"
                    + "absent)\\b[^.]{0,80}\\b(?:in|across|throughout|under) (?:the )?(?:whole |entire |"
                    + "reviewed |pinned |checked-out |rest of the )?(?:repository|repo|codebase|code base|"
                    + "checkout|project|application|source tree|src)\\b(?!\\.\\w|[ -](?:code|manifest|"
                    + "inventory|policy|board|tracker)\\b)",
            "\\b(?:no|none|nothing|not any|nowhere|never|lacks?|without|missing|"
                    + "absent)\\b[^.]{0,80}\\b(?:anywhere|elsewhere) (?:in|across|throughout|"
                    + "under) (?:the )?(?:whole |entire |reviewed |pinned |checked-out |"
                    + "rest of the )?(?:code|app|source|sources)\\b(?![ -](?:file|files|change|diff)\\b)",
            "\\bno other files?\\b(?! (?:in|of) (?:the|this) (?:change|diff|commit|"
                    + "pull request)\\b)[^.;]{0,30}\\b(?:references?|imports?|calls?|uses?|instantiates?|"
                    + "consumes?|wires?|depends? on|handles?|catches?|validates?)\\b|\\b(?:referenced|"
                    + "imported|called|used|instantiated|consumed|handled) (?:by|from|in) no other files?\\b|"
                    + "\\breferences from other files\\b",
            "\\b(?:the|this) (?:whole |entire |reviewed |pinned )?(?:repository|repo|codebase|"
                    + "code base|checkout)\\b(?! (?:method|call|class|interface|layer|bean|query|lookup|"
                    + "result|field|object|instance|step|action|policy|"
                    + "settings?)s?\\b)[^.]{0,40}\\b(?:contains no|has no|holds no|defines no|declares no|"
                    + "registers no|lacks?|never|does not|doesn't)\\b|\\b(?:the|this) (?:project|"
                    + "application)\\b(?![ -](?:code|manifest|inventory|policy|board|"
                    + "tracker)\\b)[^.]{0,40}\\b(?:defines no|declares no|registers no|never)\\b",
            "\\b(?:nothing else|nothing in the (?:project|application|code ?base|repository|repo|"
                    + "checkout)|no (?:other )?(?:code|callers?)(?! path| (?:in|of)\\b)|none of (?:the|"
                    + "its) callers)\\b[^.]{0,60}\\b(?:catch(?:es)?|handles?)\\b(?!\\()");

    static final String DISCLAIMER =
            "\\b(?:not|nor|never|without) (?:\\w+ ){0,3}?(?:establish|establishes|establishing|"
                    + "established|inspect|inspected|examine|examined|search|searched|judge|judged)\\b|"
                    + "\\bnot that\\b|\\b(?:was|were) not (?:examined|inspected|judged)\\b|"
                    + "\\bno (?:[\\w-]+ ){0,8}?(?:was|were) (?:examined|inspected|judged)\\b";

    static final String CLAUSE_END =
            "(?<=[.;])[ \\t\\r\\n]+|,[ \\t\\r\\n]+(?=(?:but|and|so|yet|while|though|although)[ \\t\\r\\n])";

    private static final List<Pattern> PATTERNS =
            MARKERS.stream().map(AbsenceClaims::compile).toList();
    private static final Pattern DISCLAIMS = compile(DISCLAIMER);
    private static final Pattern CLAUSES = Pattern.compile(CLAUSE_END);

    private AbsenceClaims() {}

    private static Pattern compile(String marker) {
        return Pattern.compile(marker, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** Whether any text the result shows a reader says the repository lacks something. */
    static boolean claimed(Candidate candidate) {
        var texts = new ArrayList<String>();
        texts.add(candidate.summary());
        texts.add(candidate.explanation());
        for (var problem : candidate.problems()) {
            texts.add(problem.summary());
            texts.add(problem.explanation());
            if (problem.nextStep() != null) texts.add(problem.nextStep());
            texts.add(problem.basis().explanation());
        }
        var example = candidate.example();
        if (example != null) texts.add(example.note());
        var search = candidate.search();
        if (search != null) {
            texts.add(search.lookedFor());
            texts.add(search.boundary());
        }
        var inapplicability = candidate.inapplicability();
        if (inapplicability != null) {
            texts.add(inapplicability.prerequisite());
            texts.add(inapplicability.ruledOutBy());
        }
        for (var question : candidate.openQuestions()) {
            texts.add(question.openQuestion());
            texts.add(question.wouldSettleIt());
        }
        return texts.stream().anyMatch(AbsenceClaims::claimed);
    }

    static boolean claimed(String text) {
        return Arrays.stream(CLAUSES.split(text))
                .anyMatch(clause -> !DISCLAIMS.matcher(clause).find()
                        && PATTERNS.stream()
                                .anyMatch(pattern -> pattern.matcher(clause).find()));
    }
}
