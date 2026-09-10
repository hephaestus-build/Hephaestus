package de.tum.cit.aet.hephaestus.practices.feedback;

import java.text.BreakIterator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Removes internal grading vocabulary and malformed envelope fragments from developer-facing text. */
public final class DeveloperTextSanitizer {

    private DeveloperTextSanitizer() {}

    private static final Pattern GRADING_SENTENCE = Pattern.compile("(?i)("
            + "\\bthe\\s+practice\\s+(?:requires|defines|expects|mandates|deems|treats|considers|states|flags)\\b|"
            + "\\b(?:according to|per|under|following|violat\\w+|satisf\\w+|fail\\w*)\\s+the\\s+practice\\b|"
            + "\\b(?:OBSERVED|NOT[_ ]OBSERVED|NOT[_ ]APPLICABLE)\\s+(?:observation|finding|result|rating)\\b|"
            + "\\b(?:for|to|a|an|the)\\s+(?:OBSERVED|NOT[_ ]OBSERVED|NOT[_ ]APPLICABLE)\\s+(?:observation|finding)\\b|"
            + "\\b(?:MINOR|MAJOR|INFO|CRITICAL)\\s+(?:severity|band|bucket|tier)\\b|"
            + "\\bseverity\\s+(?:level|band|bucket|rating)\\b|"
            + "\\b(?:upper|lower|acceptable)\\s+band\\b|"
            + "\\b[≤<=>]*\\s*\\d+[\\s-]*(?:line|file)s?\\s+threshold\\b|"
            + "\\bthreshold\\s+for\\s+a\\s+\\w+\\s+(?:observation|finding)\\b|"
            + "\\braw\\s+bucket\\b|"
            + "->\\s*(?:MAJOR|MINOR|INFO|CRITICAL|OBSERVED|NOT[_ ]OBSERVED|NOT[_ ]APPLICABLE|PRESENT|ABSENT|GOOD|BAD)\\b|"
            + "\\b(?:presence|assessment)\\s+is\\s+(?:PRESENT|ABSENT|NOT[_ ]APPLICABLE|GOOD|BAD)\\b|"
            + "\\((?:PRESENT|ABSENT|NOT[_ ]APPLICABLE)\\s*,\\s*(?:GOOD|BAD)\\)|"
            + "\\b(?:DEFECT-DETECTOR|OBSERVED\\s+DISCIPLINE|GROUNDING\\s+GATE|EPIC\\s+EXCEPTION|EPIC/CORE-REQUIREMENT)\\b|"
            + "\\benriched\\s*[=:]|"
            + "\\b[AUDFN]\\s*\\+\\s*[AUDFN]\\s*==?\\s*\\d|"
            + "\\b[ADF]\\s*=\\s*\\d{2,}|"
            + "\\([A-Z]\\s*=\\s*\\d+\\)|"
            + "\\bgiving\\s+[A-Z]\\s*=\\s*\\d+\\b|"
            + "\\b(?:additions?|deletions?|changed[_ ]files?)\\s*[=:]\\s*\\d|"
            + "\\bgenerated/vendored\\s+(?:check|exclusion|dominance)\\b|"
            + "\\bpartition\\s+after\\b|"
            + "\\bnoiseFraction\\b|"
            + "\\bcross-practice\\b|"
            + "\\bsole\\s+owner\\b|"
            + "\\bdeferr(?:ing|ed|s)\\b|"
            + "\\bemit(?:ted|s|ting)?\\s+NOT[_ ]APPLICABLE\\b|"
            + "\\bsuppress(?:ed|es|ing)\\s+its\\b|"
            + "\\b(?:team-wide\\s+)?standing\\s+nudge\\b|"
            + "\\bper-MR\\s+blocker\\b|"
            + "→\\s*(?:MAJOR|MINOR|INFO|CRITICAL|OBSERVED|NOT[_ ]OBSERVED|NOT[_ ]APPLICABLE|PRESENT|ABSENT|GOOD|BAD)\\b|"
            + "\\bPer\\s+the\\s+(?:fixed\\s+)?(?:bucketing|criteria|severity\\s+rules?)\\b|"
            + "\\bunder\\s+the\\s+criteria\\b|"
            + "\\b(?:largeness|coherence|spread|epic|significance)\\s+gate\\b|"
            + "\\bsignal\\s+i{1,3}\\b|"
            + "\\bsignificance\\s+catalogue\\b|\\bcatalogue\\s+entry\\b|"
            + "\\bsub-check\\b|"
            + "\\bnon-epic\\s+body\\b|"
            + "\\bcombined\\s+severity\\b|\\bmost\\s+severe\\s+sub-result\\b|"
            + "\\bcarve-out\\b|"
            + "\\bthreshold\\s+for\\s+downgrade\\b|\\b\\d+%\\s+threshold\\b|"
            + "\\bis\\s+(?:MINOR|MAJOR|INFO|CRITICAL)\\s*(?:\\([^)]*\\)\\s*)?,?\\s+not\\s+(?:MINOR|MAJOR|INFO|CRITICAL)\\b|"
            + "\\bobservation\\s+is\\s+(?:OBSERVED|NOT[_ ]OBSERVED|NOT[_ ]APPLICABLE)\\b|"
            + "\\bcapped\\s+at\\s+(?:MINOR|MAJOR|INFO|CRITICAL)\\b|"
            + "\\bumbrella\\s+calibration\\b|"
            + "\\breason\\s+connective\\b|"
            + "\\brollup\\b|"
            + "\\bdiff_stat\\.txt\\b|\\bdiff_summary\\.md\\b|\\bmetadata\\.(?:body|json)\\b|"
            + "\\bso\\s+the\\s+diff\\s+is\\s+trusted\\b|\\bmaterial\\s+disagreement\\b|"
            + "\\bafter\\s+scanning\\b|"
            + "\\b[a-z]+(?:_[a-z]+)+\\s+(?:is|are)\\s+(?:null|present|set|empty)\\b|"
            + "\\bsub_issues_total\\b|"
            + "\\bnoise\\s+fraction\\b|"
            + "\\bseverity\\s+is\\s+(?:MINOR|MAJOR|INFO|CRITICAL)\\b|"
            + "[≤≥<>]=?\\s*0?\\.\\d+|"
            + "\\bis_draft\\b|\\bWIP\\s+token\\b|"
            + "\\bsatisf\\w+\\s+the\\s+[\\w-]+\\s+requirement\\b"
            + ")");

    private static final Pattern ENVELOPE_TAIL = Pattern.compile("[\"'\\\\]*[}\\]][\"'\\\\]+\\s*$");

    public static boolean isGradingMeta(@Nullable String text) {
        return text != null && GRADING_SENTENCE.matcher(text).find();
    }

    public static String sanitize(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return text == null ? "" : text;
        }
        // Literal "\r\n"/"\n"/"\t" escapes survive a round trip through the runner's JSON envelope.
        String unescaped = text.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\t", "    ");
        StringBuilder kept = new StringBuilder(unescaped.length());
        BreakIterator sentences = BreakIterator.getSentenceInstance(Locale.ROOT);
        sentences.setText(unescaped);
        for (int start = sentences.first(), end = sentences.next();
                end != BreakIterator.DONE;
                start = end, end = sentences.next()) {
            String sentence = unescaped.substring(start, end);
            if (!GRADING_SENTENCE.matcher(sentence).find()) {
                kept.append(sentence);
            }
        }
        String out = kept.toString();
        out = out.replaceAll("[ \\t]{2,}", " ")
                .replaceAll("[ \\t]+([.,;])", "$1")
                .replaceAll("\\n{3,}", "\n\n");
        return stripEnvelopeCorruption(out.strip());
    }

    public static String stripEnvelopeCorruption(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        Matcher m = ENVELOPE_TAIL.matcher(text);
        if (!m.find()) {
            return text;
        }
        String head = dropDuplicatedTail(text.substring(0, m.start()), 12);
        return head.replaceAll("[\"'\\\\\\s]+$", "").stripTrailing();
    }

    private static String dropDuplicatedTail(String s, int minLen) {
        int n = s.length();
        for (int len = n / 2; len >= minLen; len--) {
            String suffix = s.substring(n - len);
            int earlier = s.lastIndexOf(suffix, n - len - 1);
            if (earlier >= 0) {
                return s.substring(0, earlier + len);
            }
        }
        return s;
    }
}
