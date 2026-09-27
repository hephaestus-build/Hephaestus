package de.tum.cit.aet.hephaestus.integration.slack.mentor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

final class SlackMentorTextFilter {

    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "and", "are", "at", "be", "can", "could", "do", "for", "focus", "in", "is", "it", "look", "next",
            "of", "on", "or", "should", "the", "there", "to", "want", "we", "which", "with", "would", "you");

    private final StringBuilder pending = new StringBuilder();
    private final StringBuilder emitted = new StringBuilder();
    private final List<Set<String>> acceptedTokenSets = new ArrayList<>();
    private boolean acceptedGreeting;
    private boolean acceptedVisibleSentence;

    String onDelta(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        pending.append(normalize(text));
        String visible = drainCompleteSentences();
        if (!visible.isEmpty()) {
            return visible;
        }
        return drainUnpunctuatedPrefix();
    }

    String finish() {
        String visible = drainCompleteSentences();
        if (pending.isEmpty()) {
            return visible;
        }
        StringBuilder out = new StringBuilder(visible);
        accept(pending.toString(), out);
        pending.setLength(0);
        return out.toString();
    }

    /**
     * The prose still pending, then feedback the mentor wrote for the developer as its own paragraph. It is shown as
     * written: the duplicate and leaked-analysis checks are guesses about prose, and a guess must not hide feedback.
     */
    String feedback(String text) {
        StringBuilder out = new StringBuilder(finish());
        String paragraph = "\n\n" + normalize(text).strip() + "\n\n";
        if (emitted.isEmpty() || emitted.toString().endsWith("\n\n")) {
            paragraph = paragraph.stripLeading();
        }
        out.append(paragraph);
        emitted.append(paragraph);
        acceptedVisibleSentence = true;
        return out.toString();
    }

    static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text.replace('‑', '-')
                .replace('‐', '-')
                .replace('‒', '-')
                .replace('−', '-')
                .replace("—", "-")
                .replace("–", "-")
                .replace("…", "...")
                .replace('\u00A0', ' ')
                .replace('\u202F', ' ')
                .replace("\u200B", "")
                .replace('‘', '\'')
                .replace('’', '\'')
                .replace('“', '"')
                .replace('”', '"');
    }

    private String drainCompleteSentences() {
        StringBuilder out = new StringBuilder();
        int cut = completeSentenceCut();
        while (cut > 0) {
            accept(pending.substring(0, cut), out);
            pending.delete(0, cut);
            cut = completeSentenceCut();
        }
        return out.toString();
    }

    private int completeSentenceCut() {
        for (int i = 0; i < pending.length(); i++) {
            char c = pending.charAt(i);
            if (c == '.' || c == '?' || c == '!') {
                if (c == '.'
                        && i > 0
                        && i + 1 < pending.length()
                        && Character.isLetterOrDigit(pending.charAt(i - 1))
                        && Character.isLetterOrDigit(pending.charAt(i + 1))) {
                    continue;
                }
                int cut = i + 1;
                while (cut < pending.length() && Character.isWhitespace(pending.charAt(cut))) {
                    cut++;
                }
                return cut;
            }
        }
        return -1;
    }

    private void accept(String candidate, StringBuilder out) {
        String sentence = candidate.stripLeading();
        if (sentence.isEmpty() || isDuplicate(sentence) || isLeadingInternalAnalysis(sentence)) {
            return;
        }
        appendWithSpacing(sentence, out);
        remember(sentence);
        acceptedVisibleSentence = true;
    }

    private String drainUnpunctuatedPrefix() {
        if (pending.isEmpty() || !Character.isWhitespace(pending.charAt(pending.length() - 1))) {
            return "";
        }
        String out = pending.toString();
        pending.setLength(0);
        emitted.append(out);
        return out;
    }

    private boolean isDuplicate(String sentence) {
        String lower = sentence.trim().toLowerCase(Locale.ROOT);
        if (isGreeting(lower)) {
            return acceptedGreeting;
        }
        Set<String> tokens = meaningfulTokens(lower);
        if (tokens.size() < 4) {
            return false;
        }
        for (Set<String> accepted : acceptedTokenSets) {
            if (accepted.size() >= 4 && jaccard(tokens, accepted) >= 0.72) {
                return true;
            }
        }
        return false;
    }

    private void remember(String sentence) {
        String lower = sentence.trim().toLowerCase(Locale.ROOT);
        if (isGreeting(lower)) {
            acceptedGreeting = true;
        }
        Set<String> tokens = meaningfulTokens(lower);
        if (!tokens.isEmpty()) {
            acceptedTokenSets.add(tokens);
        }
    }

    private void appendWithSpacing(String sentence, StringBuilder out) {
        if (!emitted.isEmpty() && needsSpace(emitted.charAt(emitted.length() - 1), sentence.charAt(0))) {
            out.append(' ');
            emitted.append(' ');
        }
        out.append(sentence);
        emitted.append(sentence);
    }

    private static boolean isGreeting(String lower) {
        return lower.matches("^(hey|hi|hello)( there)?[.!?]?$");
    }

    private boolean isLeadingInternalAnalysis(String sentence) {
        if (acceptedVisibleSentence || sentence == null || sentence.isBlank()) {
            return false;
        }
        String normalized = sentence.stripLeading().toLowerCase(Locale.ROOT);
        return normalized.matches(
                "^(user (wants|asks|is asking|asked|said)|we need to|need to|i need to|allowed paths:|use function fetch_context|according to (the )?(instructions|guidelines)).*");
    }

    private static Set<String> meaningfulTokens(String lower) {
        return Arrays.stream(lower.replaceAll("[^a-z0-9#]+", " ").trim().split("\\s+"))
                .filter(token -> !token.isBlank())
                .filter(token -> token.length() > 2
                        || token.startsWith("#")
                        || token.chars().allMatch(Character::isDigit))
                .filter(token -> !STOPWORDS.contains(token))
                .collect(Collectors.toSet());
    }

    private static double jaccard(Set<String> left, Set<String> right) {
        long intersection = left.stream().filter(right::contains).count();
        int union = left.size() + right.size() - (int) intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    private static boolean needsSpace(char previous, char next) {
        return !Character.isWhitespace(previous) && !Character.isWhitespace(next);
    }
}
