package de.tum.cit.aet.hephaestus.evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The words that state a number, for every check that refuses a count which Hephaestus did not verify: lasting advice
 * ({@code AdviceText}) and composed feedback ({@code FeedbackTextPolicy}). A text is read as lowercase words. Every
 * character that is not a letter or a digit breaks a word, so punctuation, symbols, dashes, line breaks and Markdown
 * separate words.
 */
public final class NumberWords {
    /** Anything that is not a letter or a digit: punctuation, symbols, dashes and Markdown. */
    private static final Pattern NOT_A_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    /** A word that starts with a digit, such as {@code 3}, {@code 2nd} or {@code 3prs}. */
    private static final Pattern NUMERAL = Pattern.compile("\\p{N}+\\p{L}*");

    /** The words for an exact count that has one value, and that value. */
    private static final Map<String, Integer> VALUES = Map.ofEntries(
            Map.entry("zero", 0),
            Map.entry("one", 1),
            Map.entry("two", 2),
            Map.entry("three", 3),
            Map.entry("four", 4),
            Map.entry("five", 5),
            Map.entry("six", 6),
            Map.entry("seven", 7),
            Map.entry("eight", 8),
            Map.entry("nine", 9),
            Map.entry("ten", 10),
            Map.entry("eleven", 11),
            Map.entry("twelve", 12),
            Map.entry("thirteen", 13),
            Map.entry("fourteen", 14),
            Map.entry("fifteen", 15),
            Map.entry("sixteen", 16),
            Map.entry("seventeen", 17),
            Map.entry("eighteen", 18),
            Map.entry("nineteen", 19),
            Map.entry("twenty", 20),
            Map.entry("thirty", 30),
            Map.entry("forty", 40),
            Map.entry("fifty", 50),
            Map.entry("sixty", 60),
            Map.entry("seventy", 70),
            Map.entry("eighty", 80),
            Map.entry("ninety", 90));

    /** The other words for a count, whose value the word alone does not fix. */
    private static final Set<String> MAGNITUDES =
            Set.of("hundred", "hundreds", "thousand", "thousands", "million", "millions", "dozen", "dozens");

    /** The words for an amount, a repetition or a position that name no numeral. */
    private static final Set<String> OTHER_NUMBERS = Set.of(
            "both",
            "couple",
            "pair",
            "trio",
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

    private NumberWords() {}

    /** The text as lowercase words, without the characters that break them. */
    public static List<String> words(String text) {
        return NOT_A_WORD
                .splitAsStream(text.toLowerCase(Locale.ROOT))
                .filter(word -> !word.isEmpty())
                .toList();
    }

    /** Whether the word states an exact count: a numeral, or a word such as "fourteen" or "dozen". */
    public static boolean isCount(String word) {
        return VALUES.containsKey(word)
                || MAGNITUDES.contains(word)
                || NUMERAL.matcher(word).matches();
    }

    /**
     * The form in which two counts compare: a word with one value as its digits, so "five" and "5" are the same count.
     * Any other count stays as it is.
     */
    public static String value(String count) {
        Integer value = VALUES.get(count);
        return value == null ? count : value.toString();
    }

    /**
     * The counts in a text's words, in order: each numeral or count word as it is, and a tens word with a following unit
     * ("twenty-one", "twenty one") as the one value they state, such as {@code 21}.
     */
    public static List<String> counts(List<String> words) {
        var counts = new ArrayList<String>();
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            Integer tens = VALUES.get(word);
            Integer unit = i + 1 < words.size() ? VALUES.get(words.get(i + 1)) : null;
            if (tens != null && tens >= 20 && unit != null && unit >= 1 && unit <= 9) {
                counts.add(Integer.toString(tens + unit));
                i++;
            } else if (isCount(word)) counts.add(word);
        }
        return counts;
    }

    /** Whether the word states any number: a count, or a word such as "several", "twice" or "third". */
    public static boolean isNumber(String word) {
        return isCount(word) || OTHER_NUMBERS.contains(word);
    }
}
