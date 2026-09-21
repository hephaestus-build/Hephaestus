package de.tum.cit.aet.hephaestus.agent.handler;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** New-side lines available for inline placement in the captured diff. */
class DiffHunkValidator {

    /** Regex for unified diff hunk headers: @@ -old,count +new,count @@ */
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@");

    /**
     * Parse a plain or line-annotated unified diff into valid new-side line numbers per file.
     *
     * @param diff the unified diff output (from git diff)
     * @return map of file path → sorted set of valid new-side line numbers
     */
    static Map<String, TreeSet<Integer>> parseValidLines(@Nullable String diff) {
        Map<String, TreeSet<Integer>> result = new HashMap<>();
        if (diff == null || diff.isBlank()) return result;

        String currentFile = null;
        int newLineNum = 0;

        for (String line : diff.split("\n", -1)) {
            // Strip [L<n>] annotation prefix if present (annotated diff)
            String effectiveLine = line;
            if (line.startsWith("[L") && line.contains("] ")) {
                effectiveLine = line.substring(line.indexOf("] ") + 2);
            }

            if (effectiveLine.startsWith("diff --git")) {
                // Best-effort path from the `diff --git a/.. b/..` line. git quotes paths containing spaces
                // (`diff --git "a/x y" "b/x y"`), which mangles this heuristic, so it is only a fallback — the
                // canonical new-path is the `+++ b/` line parsed below, which always precedes the file's hunks.
                currentFile = parseGitHeaderPath(effectiveLine);
                if (currentFile != null) {
                    result.putIfAbsent(currentFile, new TreeSet<>());
                }
                newLineNum = 0;
                continue;
            }

            // A `+++ b/path` header only appears between `diff --git` and the first hunk (newLineNum still 0);
            // inside a hunk newLineNum is non-zero, so a real added source line like `+++ foo` is never
            // misread as a file header.
            if (newLineNum == 0 && effectiveLine.startsWith("+++ ")) {
                // Canonical new-side path for this file. Overrides the `diff --git` heuristic so a quoted /
                // space-containing path is matched reliably (it is unquoted-stripped here). /dev/null (deletes)
                // yields no usable path and is ignored.
                String plusPath = parsePlusPath(effectiveLine);
                if (plusPath != null) {
                    currentFile = plusPath;
                    result.putIfAbsent(currentFile, new TreeSet<>());
                }
                continue;
            }

            Matcher m = HUNK_HEADER.matcher(effectiveLine);
            if (m.find()) {
                newLineNum = Integer.parseInt(m.group(1));
                continue;
            }

            if (newLineNum == 0 || currentFile == null) continue;

            // Everything else — a deleted line, the "\ No newline at end of file" marker, a trailing
            // blank — is invisible on the new side and must not advance the counter, or every note
            // after it anchors a line too low.
            if (effectiveLine.startsWith("+") || effectiveLine.startsWith(" ")) {
                result.computeIfAbsent(currentFile, ignored -> new TreeSet<>()).add(newLineNum);
                newLineNum++;
            }
        }

        return result;
    }

    /**
     * Fallback file path from a {@code diff --git a/.. b/..} header via the last {@code  b/} segment, with
     * surrounding quotes stripped (git quotes paths containing spaces). Returns null when no {@code  b/}
     * segment is present.
     */
    private static @Nullable String parseGitHeaderPath(String header) {
        int bIdx = header.lastIndexOf(" b/");
        if (bIdx <= 0) {
            return null;
        }
        return stripQuotes(header.substring(bIdx + 3));
    }

    /**
     * Canonical new-side path from a {@code +++ b/path} (or {@code +++ path}) header: strip the trailing
     * tab-and-metadata, surrounding quotes, and the {@code b/} prefix. Returns null for {@code /dev/null}
     * (a delete has no new-side path).
     */
    private static @Nullable String parsePlusPath(String header) {
        String p = header.substring(4).trim();
        int tab = p.indexOf('\t');
        if (tab >= 0) {
            p = p.substring(0, tab);
        }
        p = stripQuotes(p);
        if (p.startsWith("b/")) {
            p = p.substring(2);
        }
        return p.equals("/dev/null") ? null : p;
    }

    /** Strip a single pair of surrounding double quotes (git quotes paths with spaces). */
    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
