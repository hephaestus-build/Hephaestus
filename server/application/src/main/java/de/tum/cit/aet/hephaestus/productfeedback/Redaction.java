package de.tum.cit.aet.hephaestus.productfeedback;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic removal of personal data and secrets from product feedback text. It runs on the person's text before
 * a model sees it, on the model's draft, and again on every edit. It cannot find everything, which is why a blocking
 * finding keeps a report off the public route instead of trusting the removal.
 */
final class Redaction {
    private Redaction() {}

    enum Removal {
        HIDDEN,
        SECRET,
        EMAIL,
        LINK,
        NAME,
        WORKSPACE;

        /** A finding that keeps the report off the public route even though the text was removed. */
        boolean blocking() {
            return this == SECRET || this == EMAIL || this == NAME;
        }
    }

    /** What the instance knows about the reporter and their workspaces. Names block the public route; workspace names do not. */
    record Identities(Collection<String> names, Collection<String> workspaceTerms) {
        static final Identities NONE = new Identities(List.of(), List.of());
    }

    record Result(String text, Map<Removal, Integer> counts) {
        boolean blocking() {
            return counts.keySet().stream().anyMatch(Removal::blocking);
        }
    }

    private static final Pattern HTML_COMMENT = Pattern.compile("<!--.*?(-->|$)", Pattern.DOTALL);
    private static final Pattern HTML_TAG = Pattern.compile("</?[A-Za-z!][^>]*>");
    // Zero-width, bidirectional and other invisible format characters, and control characters except tab and newline.
    private static final Pattern INVISIBLE =
            Pattern.compile("[\\u00AD\\u180E\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u2069\\uFEFF"
                    + "\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");
    private static final List<Pattern> SECRETS = List.of(
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(-----END [A-Z ]*PRIVATE KEY-----|$)"),
            Pattern.compile("\\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{16,}"),
            Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{20,}"),
            Pattern.compile("\\bglpat-[A-Za-z0-9_-]{16,}"),
            Pattern.compile("\\bxox[abposr]-[A-Za-z0-9-]{10,}"),
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{16,}"),
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
            Pattern.compile("\\b[A-Fa-f0-9]{32,}\\b"),
            // A long run of base64 or URL-safe characters that mixes cases and digits: a key, not a word.
            Pattern.compile("\\b(?=[A-Za-z0-9+/_-]*[0-9])(?=[A-Za-z0-9+/_-]*[a-z])(?=[A-Za-z0-9+/_-]*[A-Z])"
                    + "[A-Za-z0-9+/_-]{32,}={0,2}"));
    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");
    private static final Pattern URL =
            Pattern.compile("\\b(?:https?://|www\\.)[^\\s<>()\\[\\]]+", Pattern.CASE_INSENSITIVE);
    // A bare domain is not a live link on GitHub, but spam relies on it being read. The list keeps file names such as
    // "notes.txt" and terms such as "Node.js" out.
    private static final Pattern BARE_DOMAIN = Pattern.compile(
            "\\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+(?:com|net|org|io|dev|app|ai|co|info|biz|xyz|ru|cn|de|eu|uk"
                    + "|me|example|online|site|shop|top|club|link|click|live)\\b(?:/[^\\s<>()\\[\\]]*)?",
            Pattern.CASE_INSENSITIVE);

    /** Removes what a reader cannot see but a model or a renderer would act on: HTML comments, tags and invisible characters. */
    static String stripHidden(String text) {
        String withoutComments = HTML_COMMENT.matcher(text).replaceAll(" ");
        String withoutTags = HTML_TAG.matcher(withoutComments).replaceAll(" ");
        return INVISIBLE.matcher(withoutTags).replaceAll("");
    }

    /**
     * @param repository {@code owner/name} of the target repository; a link to one of its issues or pull requests
     *     survives, every other link is removed
     */
    static Result redact(String text, Identities identities, String repository) {
        var counts = new EnumMap<Removal, Integer>(Removal.class);
        String stripped = stripHidden(text);
        if (!stripped.equals(text)) counts.merge(Removal.HIDDEN, 1, Integer::sum);
        String result = stripped;
        for (Pattern secret : SECRETS) result = replace(result, secret, "[secret removed]", Removal.SECRET, counts);
        result = replace(result, EMAIL, "[email removed]", Removal.EMAIL, counts);
        result = replaceLinks(result, repository, counts);
        result = replace(result, BARE_DOMAIN, "[link removed]", Removal.LINK, counts);
        result = replaceTerms(result, identities.names(), "[name removed]", Removal.NAME, counts);
        result = replaceTerms(result, identities.workspaceTerms(), "[workspace]", Removal.WORKSPACE, counts);
        return new Result(result, Map.copyOf(counts));
    }

    private static String replace(
            String text, Pattern pattern, String replacement, Removal finding, Map<Removal, Integer> counts) {
        Matcher matcher = pattern.matcher(text);
        var out = new StringBuilder();
        int found = 0;
        while (matcher.find()) {
            found++;
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        if (found > 0) counts.merge(finding, found, Integer::sum);
        return out.toString();
    }

    private static String replaceLinks(String text, String repository, Map<Removal, Integer> counts) {
        Pattern allowed = Pattern.compile(
                "https://github\\.com/" + Pattern.quote(repository) + "/(?:issues|pull)/\\d+",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = URL.matcher(text);
        var out = new StringBuilder();
        int found = 0;
        while (matcher.find()) {
            String url = trimTrailingPunctuation(matcher.group());
            String rest = matcher.group().substring(url.length());
            if (allowed.matcher(url).matches()) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
            } else {
                found++;
                matcher.appendReplacement(out, Matcher.quoteReplacement("[link removed]" + rest));
            }
        }
        matcher.appendTail(out);
        if (found > 0) counts.merge(Removal.LINK, found, Integer::sum);
        return out.toString();
    }

    private static String trimTrailingPunctuation(String url) {
        int end = url.length();
        while (end > 0 && ".,;:!?'\"".indexOf(url.charAt(end - 1)) >= 0) end--;
        return url.substring(0, end);
    }

    private static String replaceTerms(
            String text, Collection<String> terms, String replacement, Removal finding, Map<Removal, Integer> counts) {
        String result = text;
        // Longest first, so that "Jonas Weber" is removed before "Jonas".
        List<String> ordered = terms.stream()
                .map(String::strip)
                .filter(term -> term.length() >= 3)
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        for (String term : ordered) {
            Pattern pattern = Pattern.compile(
                    "(?<![\\p{L}\\p{N}])" + Pattern.quote(term) + "(?![\\p{L}\\p{N}])",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            result = replace(result, pattern, replacement, finding, counts);
        }
        return result;
    }

    /** The counts in words for the person, such as "1 email address, 2 links". */
    static String describe(Map<Removal, Integer> counts) {
        var parts = new ArrayList<String>();
        for (var entry : new EnumMap<>(counts).entrySet()) {
            int n = entry.getValue();
            String noun = switch (entry.getKey()) {
                case HIDDEN -> "hidden formatting";
                case SECRET -> n == 1 ? "secret" : "secrets";
                case EMAIL -> n == 1 ? "email address" : "email addresses";
                case LINK -> n == 1 ? "link" : "links";
                case NAME -> n == 1 ? "name" : "names";
                case WORKSPACE -> n == 1 ? "workspace name" : "workspace names";
            };
            parts.add(entry.getKey() == Removal.HIDDEN ? noun : n + " " + noun);
        }
        return String.join(", ", parts).toLowerCase(Locale.ROOT);
    }
}
