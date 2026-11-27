package de.tum.cit.aet.hephaestus.practices.advice;

import de.tum.cit.aet.hephaestus.evidence.HiddenCharacters;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The words that advice may use (ADR 0160). Advice describes what one review observed. The Practice Profile adds how
 * many items it saw this in, from the live observations, so the text itself never counts work or claims a pattern.
 *
 * <p>The count rule refuses when in doubt: a false refusal writes no advice, but a false admission puts a count on the
 * profile that the view must compute. It reads each sentence as plain lowercase words, so punctuation, dashes and
 * Markdown cannot hide a count.
 */
final class AdviceText {
    static final int MAX_LENGTH = 2000;

    private static final Pattern PATTERN_CLAIM = Pattern.compile(
            "\\b(again|every time|each time|repeatedly|recurring|patterns?|often|usually|consistently|you keep|keeps)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern SENTENCE_END = Pattern.compile("[.!?\\n]+");
    /** Anything that is not a letter or a digit: punctuation, symbols, dashes and Markdown. */
    private static final Pattern NOT_A_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private static final Pattern NUMERAL = Pattern.compile("\\p{N}+\\p{L}*");
    private static final Set<String> NUMBER_WORDS = Set.of(
            "zero",
            "one",
            "two",
            "three",
            "four",
            "five",
            "six",
            "seven",
            "eight",
            "nine",
            "ten",
            "eleven",
            "twelve",
            "thirteen",
            "fourteen",
            "fifteen",
            "sixteen",
            "seventeen",
            "eighteen",
            "nineteen",
            "twenty",
            "thirty",
            "forty",
            "fifty",
            "sixty",
            "seventy",
            "eighty",
            "ninety",
            "hundred",
            "hundreds",
            "thousand",
            "thousands",
            "dozen",
            "dozens",
            "both",
            "couple",
            "pair",
            "few",
            "handful",
            "several",
            "many",
            "multiple",
            "numerous",
            "various",
            "single",
            "first",
            "second",
            "third",
            "fourth",
            "fifth",
            "sixth",
            "seventh",
            "eighth",
            "ninth",
            "tenth");
    private static final Set<String> SINGULAR_WORK = Set.of("pr", "mr", "issue", "review");
    private static final Set<String> PLURAL_WORK = Set.of("prs", "mrs", "issues", "reviews");

    private enum Work {
        NONE,
        SINGULAR,
        PLURAL
    }

    private AdviceText() {}

    /** Whether the text can be stored as advice. */
    static boolean admits(String text) {
        return !text.isBlank()
                && text.length() <= MAX_LENGTH
                && !HiddenCharacters.hides(text)
                && !PATTERN_CLAIM.matcher(text).find()
                && !countsWork(text);
    }

    /**
     * Whether a sentence counts work. A plural work noun counts with any number in its sentence, before or after it. A
     * singular one counts with a number at most two words before it, as in "one more pull request". A work noun right
     * after "before" or "after" is a moment, not work, so "two tests before review" counts nothing.
     */
    private static boolean countsWork(String text) {
        for (String sentence : SENTENCE_END.splitAsStream(text).toList()) {
            List<String> words = words(sentence);
            boolean anyNumber = words.stream().anyMatch(AdviceText::isNumber);
            for (int i = 0; i < words.size(); i++) {
                Work work = workAt(words, i);
                if (work == Work.NONE) continue;
                if (i > 0
                        && (words.get(i - 1).equals("before")
                                || words.get(i - 1).equals("after"))) continue;
                boolean counted = work == Work.PLURAL
                        ? anyNumber
                        : words.subList(Math.max(0, i - 3), i).stream().anyMatch(AdviceText::isNumber);
                if (counted) return true;
            }
        }
        return false;
    }

    /** The work noun that starts at {@code i}: "pull request", "merge request", "pr", "mr", "issue" or "review". */
    private static Work workAt(List<String> words, int i) {
        String word = words.get(i);
        if ((word.equals("pull") || word.equals("merge")) && i + 1 < words.size()) {
            String next = words.get(i + 1);
            if (next.equals("request")) return Work.SINGULAR;
            if (next.equals("requests")) return Work.PLURAL;
        }
        if (PLURAL_WORK.contains(word)) return Work.PLURAL;
        if (SINGULAR_WORK.contains(word)) return Work.SINGULAR;
        return Work.NONE;
    }

    private static boolean isNumber(String word) {
        return NUMBER_WORDS.contains(word) || NUMERAL.matcher(word).matches();
    }

    /** The sentence as lowercase words: punctuation, symbols, dashes and Markdown become word breaks. */
    private static List<String> words(String sentence) {
        return NOT_A_WORD
                .splitAsStream(sentence.toLowerCase(Locale.ROOT))
                .filter(word -> !word.isEmpty())
                .toList();
    }
}
