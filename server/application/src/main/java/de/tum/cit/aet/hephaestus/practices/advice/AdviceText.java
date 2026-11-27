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
 * profile that the view must compute. Text that names both a number and a piece of work is refused, wherever in the text
 * the two stand, because no word distance or sentence break tells a count of work from a number about one review.
 */
final class AdviceText {
    static final int MAX_LENGTH = 2000;

    private static final Pattern PATTERN_CLAIM = Pattern.compile(
            "\\b(again|every time|each time|repeatedly|recurring|patterns?|often|usually|consistently|you keep|keeps)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Anything that is not a letter or a digit: punctuation, symbols, dashes and Markdown. */
    private static final Pattern NOT_A_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private static final String WORK_NOUN = "(?:(?:pull|merge) +requests?|prs?|mrs?|issues?|reviews?)";

    /**
     * A work noun right after "before" or "after", with only spaces between them, is a moment, not work: "add two tests
     * before review". Any other character between them, such as a full stop or a line break, keeps the noun.
     */
    private static final Pattern MOMENT = Pattern.compile("\\b(?:before|after) +" + WORK_NOUN + "\\b");

    /** A numeral written against a work noun, such as "3PRs", is a number and a work noun. */
    private static final Pattern NUMERAL_ON_WORK = Pattern.compile("(\\p{N})(?=" + WORK_NOUN + "\\b)");

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
            "once",
            "twice",
            "thrice",
            "trio",
            "million",
            "millions",
            "first",
            "second",
            "third",
            "fourth",
            "fifth",
            "sixth",
            "seventh",
            "eighth",
            "ninth",
            "tenth",
            "eleventh",
            "twelfth",
            "thirteenth",
            "fourteenth",
            "fifteenth",
            "sixteenth",
            "seventeenth",
            "eighteenth",
            "nineteenth",
            "twentieth",
            "thirtieth",
            "fortieth",
            "fiftieth",
            "sixtieth",
            "seventieth",
            "eightieth",
            "ninetieth",
            "hundredth",
            "thousandth");
    private static final Set<String> WORK = Set.of("pr", "prs", "mr", "mrs", "issue", "issues", "review", "reviews");

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
     * Whether the text names a number and a piece of work: a pull or merge request, a PR, an MR, an issue or a review. A
     * moment ({@link #MOMENT}) is not work, so "add two tests before review" counts nothing.
     */
    private static boolean countsWork(String text) {
        String lower = NUMERAL_ON_WORK.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("$1 ");
        if (words(lower).stream().noneMatch(AdviceText::isNumber)) return false;
        List<String> work = words(MOMENT.matcher(lower).replaceAll(" "));
        for (int i = 0; i < work.size(); i++) if (workAt(work, i)) return true;
        return false;
    }

    /** Whether a work noun starts at {@code i}; "request" after "pull" or "merge" belongs to that noun. */
    private static boolean workAt(List<String> words, int i) {
        String word = words.get(i);
        if ((word.equals("pull") || word.equals("merge")) && i + 1 < words.size()) {
            String next = words.get(i + 1);
            return next.equals("request") || next.equals("requests");
        }
        return WORK.contains(word);
    }

    private static boolean isNumber(String word) {
        return NUMBER_WORDS.contains(word) || NUMERAL.matcher(word).matches();
    }

    /** The lowercase text as words: punctuation, symbols, dashes, line breaks and Markdown become word breaks. */
    private static List<String> words(String lower) {
        return NOT_A_WORD.splitAsStream(lower).filter(word -> !word.isEmpty()).toList();
    }
}
