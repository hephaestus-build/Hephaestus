package de.tum.cit.aet.hephaestus.agent.composer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The wording that a conversation note must not carry. A note is written for Hephaestus, which decides how to raise the
 * point in a later answer, so it speaks about the developer, never to them. It also carries none of the review's
 * internal words, the labelling rubric's Q3: no outcome code, severity, score, threshold, practice slug, field label or
 * line coordinate. Quoted words of the work are the work's own and are not checked.
 */
final class NoteWording {
    private NoteWording() {}

    // A quote of the work: straight or typographic double quotes, or a code span.
    private static final Pattern QUOTED = Pattern.compile("\"[^\"]*\"|“[^”]*”|`[^`]*`");

    private static final Pattern DIRECT_ADDRESS =
            Pattern.compile("(?i)(?<![\\p{L}\\p{N}'’])(?:you|your|yours|yourself|yourselves)(?![\\p{L}\\p{N}])");

    private static final Pattern OUTCOME_CODE =
            Pattern.compile("(?<![\\p{L}\\p{N}_])(?:NOT_MET|DOES_NOT_APPLY|COULD_NOT_DETERMINE)(?![\\p{L}\\p{N}_])");

    private static final Pattern INTERNAL_WORD = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}])(?:severity|severities|thresholds?|confidence|score[sd]?)(?![\\p{L}\\p{N}])");

    // "L16", "line 12", "lines 9-15" and "Foo.java:12": coordinates stay in the source evidence.
    private static final Pattern COORDINATE =
            Pattern.compile("(?i)(?<![\\p{L}\\p{N}])(?:L\\d+|lines?\\s+\\d+(?:\\s*[-–]\\s*\\d+)?)(?![\\p{L}\\p{N}])"
                    + "|[\\p{L}\\p{N}_./-]+\\.\\p{L}{1,8}:\\d+");

    private static final Pattern FIELD_LABEL = Pattern.compile(
            "(?i)^\\s*(?:situation|capability|evidence|next step|in conversation|earlier feedback)\\s*:");

    /** What the note says that it must not, one sentence for each rule it breaks; empty when it may stay. */
    static List<String> problems(String note, Collection<String> practiceSlugs) {
        String prose = QUOTED.matcher(note).replaceAll(" ");
        var problems = new ArrayList<String>();
        if (DIRECT_ADDRESS.matcher(prose).find())
            problems.add("speaks to the developer; write about them, as \"the developer\", for Hephaestus to read");
        if (OUTCOME_CODE.matcher(prose).find()
                || INTERNAL_WORD.matcher(prose).find()
                || practiceSlugs.stream().anyMatch(slug -> slug.contains("-") && contains(prose, slug)))
            problems.add("uses an internal word (an outcome code, severity, score, threshold or practice slug);"
                    + " say what the work shows instead");
        if (COORDINATE.matcher(prose).find())
            problems.add("names a line coordinate; refer to the evidence naturally, such as \"the description's"
                    + " closing line\", and keep coordinates in basedOn");
        if (FIELD_LABEL.matcher(prose).find()) problems.add("starts with a field label; write only the field's fact");
        return problems;
    }

    private static boolean contains(String prose, String slug) {
        return Pattern.compile(
                        "(?<![\\p{L}\\p{N}-])" + Pattern.quote(slug.toLowerCase(Locale.ROOT)) + "(?![\\p{L}\\p{N}-])")
                .matcher(prose.toLowerCase(Locale.ROOT))
                .find();
    }
}
