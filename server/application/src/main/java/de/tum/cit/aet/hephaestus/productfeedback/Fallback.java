package de.tum.cit.aet.hephaestus.productfeedback;

import java.util.List;

/** The issue a private report becomes without a model draft: the person's own text, after redaction. */
final class Fallback {
    private Fallback() {}

    static Draft of(ProductFeedback.Kind kind, String redactedText) {
        String firstLine = redactedText.strip().lines().findFirst().orElse("Product feedback");
        String title =
                firstLine.length() <= Draft.TITLE_MAX ? firstLine : firstLine.substring(0, Draft.TITLE_MAX - 1) + "…";
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
