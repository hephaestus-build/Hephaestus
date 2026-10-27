package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.handler.candidate.Candidate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Words that say the repository, beyond the lines a result cites, lacks something: that nothing elsewhere handles a
 * failure, or that the code base defines no such thing. Only a search of the whole checkout can show that (ADR 0064 §
 * Absence). This is a short list of markers, not a reading of the prose. It catches the usual wording of such a claim
 * and leaves bounded claims about the cited lines alone. {@code pi-session-reads.ts} holds the same markers, so the
 * runner asks the session once before admission withholds the result.
 */
final class AbsenceClaims {

    static final List<String> MARKERS = List.of(
            "\\b(?:no|none|nothing|not any|nowhere|never|lacks?|without|missing|"
                    + "absent)\\b[^.]{0,80}(?:\\b(?:in|across|throughout) the (?:whole |entire |reviewed |"
                    + "rest of the )?(?:repository|repo|codebase|code base|checkout|project|application)\\b|"
                    + "\\b(?:elsewhere|anywhere|other files?)\\b)",
            "\\b(?:the|this) (?:whole |entire |reviewed )?(?:repository|repo|codebase|code base|"
                    + "checkout)\\b(?! (?:method|call|class|interface|layer|bean|query|lookup|result|field|object|"
                    + "instance)s?\\b)[^.]{0,40}\\b(?:contains no|has no|holds no|defines no|declares no|"
                    + "registers no|lacks?)\\b|\\b(?:the|this) (?:project|application)\\b[^.]{0,40}\\b(?:defines no|"
                    + "declares no|registers no)\\b",
            "\\b(?:nothing else|nothing in the (?:project|application|code ?base|repository|repo|"
                    + "checkout)|no (?:other )?(?:code|callers?)|none of (?:the|"
                    + "its) callers)\\b[^.]{0,60}\\b(?:catch(?:es)?|handles?)\\b");

    private static final List<Pattern> PATTERNS = MARKERS.stream()
            .map(marker -> Pattern.compile(marker, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE))
            .toList();

    private AbsenceClaims() {}

    /** Whether any text the result shows a reader says the repository lacks something. */
    static boolean claimed(Candidate candidate) {
        var texts = new ArrayList<String>();
        texts.add(candidate.summary());
        texts.add(candidate.explanation());
        for (var problem : candidate.problems()) {
            texts.add(problem.summary());
            texts.add(problem.explanation());
            if (problem.nextStep() != null) texts.add(problem.nextStep());
        }
        var example = candidate.example();
        if (example != null) texts.add(example.note());
        var inapplicability = candidate.inapplicability();
        if (inapplicability != null) texts.add(inapplicability.ruledOutBy());
        return texts.stream().anyMatch(AbsenceClaims::claimed);
    }

    static boolean claimed(String text) {
        return PATTERNS.stream().anyMatch(pattern -> pattern.matcher(text).find());
    }
}
