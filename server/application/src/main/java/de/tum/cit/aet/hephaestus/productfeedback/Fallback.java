package de.tum.cit.aet.hephaestus.productfeedback;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The issue a private report becomes without a model draft: the person's own text, after redaction. */
final class Fallback {
    private static final int TITLE_MAX = 200;
    private static final Pattern FIRST_SENTENCE = Pattern.compile("^(.+?[.!?])(?:\\s|$)");

    private Fallback() {}

    /** GitHub takes 256 characters. The first sentence reads as a title; the body keeps every word anyway. */
    private static String title(String firstLine) {
        Matcher sentence = FIRST_SENTENCE.matcher(firstLine);
        String title = sentence.find() ? sentence.group(1) : firstLine;
        if (title.length() <= TITLE_MAX) return title;
        int word = title.lastIndexOf(' ', TITLE_MAX - 1);
        return title.substring(0, word > 0 ? word : TITLE_MAX - 1) + "…";
    }

    static Draft of(ProductFeedback.Kind kind, String redactedText) {
        String title = title(redactedText.strip().lines().findFirst().orElse("Product feedback"));
        boolean bug = kind == ProductFeedback.Kind.BUG;
        return new Draft(
                Draft.Disposition.NOT_AN_ISSUE,
                bug ? Draft.Type.BUG : Draft.Type.FEATURE,
                title,
                bug ? redactedText : "",
                "",
                List.of(),
                bug ? "" : redactedText,
                "",
                List.of(),
                "",
                null,
                false,
                false,
                false,
                Draft.Confidence.LOW);
    }
}
