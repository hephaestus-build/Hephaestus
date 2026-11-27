package de.tum.cit.aet.hephaestus.practices.advice;

import de.tum.cit.aet.hephaestus.evidence.HiddenCharacters;
import java.util.regex.Pattern;

/**
 * The words that advice may use (ADR 0160). Advice describes what one review observed. The Practice Profile adds how
 * many items it saw this in, from the live observations, so the text itself never counts work or claims a pattern.
 */
final class AdviceText {
    static final int MAX_LENGTH = 2000;

    private static final Pattern PATTERN_CLAIM = Pattern.compile(
            "\\b(again|every time|each time|repeatedly|recurring|patterns?|often|usually|consistently|you keep|keeps)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * A count of work: a number, then within two words a plural work noun, with no preposition between. A count of two
     * or more takes the plural, so "two tests before review" names no count of work, and "two in-flight pull requests"
     * does.
     */
    private static final Pattern COUNT_OF_WORK = Pattern.compile(
            "\\b(\\d+|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen"
                    + "|seventeen|eighteen|nineteen|twenty|dozens?|several|many|multiple|numerous|a few)\\s+"
                    + "(?:(?!(?:in|on|for|from|this|that|before|after|during|to|per|with)\\s)[\\w-]+\\s+){0,2}"
                    + "(?:of\\s+(?:your|the|these|those)\\s+)?"
                    + "(pull requests|merge requests|PRs|MRs|issues|reviews)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private AdviceText() {}

    /** Whether the text can be stored as advice. */
    static boolean admits(String text) {
        return !text.isBlank()
                && text.length() <= MAX_LENGTH
                && !HiddenCharacters.hides(text)
                && !PATTERN_CLAIM.matcher(text).find()
                && !COUNT_OF_WORK.matcher(text).find();
    }
}
