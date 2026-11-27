package de.tum.cit.aet.hephaestus.practices.advice;

import de.tum.cit.aet.hephaestus.evidence.HiddenCharacters;
import java.util.Locale;
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
     * A count of work: a number, then within three words a work noun, singular or plural, matched on the text as plain
     * lowercase words ({@link #words}). It refuses when in doubt: a false refusal writes no advice, but a false admission
     * puts a count on the profile that the view must compute (ADR 0160). Only "before" or "after" ends the count, so
     * "two tests before review" names no work.
     */
    private static final Pattern COUNT_OF_WORK = Pattern.compile(
            "\\b(\\d+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen"
                    + "|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundreds?|dozens?"
                    + "|both|couple|pair|few|several|many|multiple|numerous|various) "
                    + "(?:(?!(?:before|after) )\\S+ ){0,3}"
                    + "(pull requests?|merge requests?|prs?|mrs?|issues?|reviews?)\\b");

    /** Anything that is not a letter or a digit: punctuation, symbols, dashes and Markdown. */
    private static final Pattern NOT_A_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private AdviceText() {}

    /** Whether the text can be stored as advice. */
    static boolean admits(String text) {
        return !text.isBlank()
                && text.length() <= MAX_LENGTH
                && !HiddenCharacters.hides(text)
                && !PATTERN_CLAIM.matcher(text).find()
                && !COUNT_OF_WORK.matcher(words(text)).find();
    }

    /** The text as lowercase words with one space between them, so formatting cannot split a count from its noun. */
    private static String words(String text) {
        return NOT_A_WORD.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }
}
