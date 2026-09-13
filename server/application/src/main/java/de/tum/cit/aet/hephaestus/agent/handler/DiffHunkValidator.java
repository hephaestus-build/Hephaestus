package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.DiffNote;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates and corrects diff note line positions against the actual diff hunks.
 *
 * <p>Parses the unified diff to extract which lines are valid targets for diff notes
 * (added lines and context lines within hunks), then validates each diff note's position.
 * Invalid positions are snapped to the nearest valid line in the same file.
 *
 * <p>This is a server-side safety net: even if the agent produces wrong line numbers,
 * the notes will still land on valid diff lines rather than failing at the GitLab API.
 */
class DiffHunkValidator {

    private static final Logger log = LoggerFactory.getLogger(DiffHunkValidator.class);

    /** Regex for unified diff hunk headers: @@ -old,count +new,count @@ */
    private static final Pattern HUNK_HEADER = Pattern.compile("@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@");

    /**
     * Parse a unified diff (plain, NOT annotated with [L<n>]) and extract valid new-side
     * line numbers per file. These are the lines that GitLab will accept for diff notes.
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
     * Snap window: maximum line delta we'll silently fix before dropping. A diff note at L42 when
     * only L500 is valid is more confusing than no note at all — the student sees an unrelated
     * comment and has to invert the agent's intent. Notes beyond the window are dropped; the observation's
     * detail still appears in the server-composed MR summary because only delivered notes demote their
     * summary section.
     */
    static final int MAX_SNAP_DELTA = 10;

    /**
     * Validate and correct diff note positions against the valid line map.
     *
     * <p>For each note:
     * <ul>
     *   <li>If the line is valid → keep as-is</li>
     *   <li>If the line is invalid but within {@link #MAX_SNAP_DELTA} of a valid line → snap</li>
     *   <li>If the nearest valid line is &gt; {@link #MAX_SNAP_DELTA} away → drop the note</li>
     *   <li>If the file doesn't exist in the diff → keep as-is (will fail at API level)</li>
     * </ul>
     */
    static List<DiffNote> validateAndCorrect(
            List<DiffNote> notes, Map<String, TreeSet<Integer>> validLines, String jobId) {
        if (notes.isEmpty() || validLines.isEmpty()) return notes;

        List<DiffNote> corrected = new ArrayList<>(notes.size());
        int corrections = 0;
        int dropped = 0;

        for (DiffNote note : notes) {
            TreeSet<Integer> fileLines = validLines.get(note.filePath());
            if (fileLines == null || fileLines.isEmpty()) {
                // File not in diff — can't validate, keep as-is
                corrected.add(note);
                continue;
            }

            if (fileLines.contains(note.startLine())) {
                // startLine is valid, but a model may still emit an out-of-range or gap-crossing endLine that
                // GitHub rejects at the multi-line suggestion anchor — the exact failure this validator prevents.
                // Apply the same span-containment guard used on the snap path: collapse endLine to startLine
                // unless every line in [startLine, endLine] is a valid diff line.
                Integer validatedEnd = containedEnd(fileLines, note.startLine(), note.endLine());
                if (java.util.Objects.equals(validatedEnd, note.endLine())) {
                    corrected.add(note);
                } else {
                    corrected.add(new DiffNote(
                            note.filePath(), note.startLine(), validatedEnd, note.body(), note.deliveryKey()));
                }
                continue;
            }

            // Find nearest valid line
            Integer nearest = findNearest(fileLines, note.startLine());
            if (nearest == null) {
                corrected.add(note);
                continue;
            }

            int delta = Math.abs(nearest - note.startLine());
            if (delta > MAX_SNAP_DELTA) {
                log.info(
                        "Dropped diff note: file={}, originalLine={}, nearestValid={} (delta={} > {}), jobId={}",
                        note.filePath(),
                        note.startLine(),
                        nearest,
                        delta,
                        MAX_SNAP_DELTA,
                        jobId);
                dropped++;
                continue;
            }

            log.info(
                    "Corrected diff note position: file={}, original={}, corrected={}, jobId={}",
                    note.filePath(),
                    note.startLine(),
                    nearest,
                    jobId);
            corrections++;

            // Create corrected note with new position, preserving the original span width
            Integer correctedEnd = note.endLine();
            if (correctedEnd != null) {
                int originalSpan = Math.max(0, correctedEnd - note.startLine());
                int desiredEnd = nearest + originalSpan;
                // Find nearest valid line that doesn't expand beyond the original span, then enforce contiguity.
                Integer nearestEnd = fileLines.floor(desiredEnd);
                correctedEnd = containedEnd(fileLines, nearest, nearestEnd);
            }

            // Only the position changes — preserve the observation's correlation key so the snapped note still
            // maps back to its persisted observation (ADR 0021).
            corrected.add(new DiffNote(note.filePath(), nearest, correctedEnd, note.body(), note.deliveryKey()));
        }

        if (corrections > 0 || dropped > 0) {
            log.info("Diff note position fixup: corrected={}, dropped={}, jobId={}", corrections, dropped, jobId);
        }

        return corrected;
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

    /**
     * Validate a multi-line suggestion anchor's end line against the diff's valid lines. The
     * {@code [startLine, endLine]} range must be fully contained — a range that skips a non-diff interior
     * line (e.g. startLine=10, valid {10,12,13}, endLine=13 crosses line 11) is rejected by GitHub's
     * multi-line suggestion anchor. Returns {@code endLine} when the whole span is contiguous and valid,
     * otherwise collapses to single-line ({@code startLine}). A null {@code endLine} stays null.
     */
    private static @Nullable Integer containedEnd(
            TreeSet<Integer> fileLines, int startLine, @Nullable Integer endLine) {
        if (endLine == null || endLine <= startLine) {
            return endLine;
        }
        boolean contiguous = fileLines.subSet(startLine, true, endLine, true).size() == endLine - startLine + 1;
        return contiguous ? endLine : startLine;
    }

    private static @Nullable Integer findNearest(TreeSet<Integer> set, int target) {
        Integer floor = set.floor(target);
        Integer ceiling = set.ceiling(target);

        if (floor == null && ceiling == null) return null;
        if (floor == null) return ceiling;
        if (ceiling == null) return floor;

        return (target - floor <= ceiling - target) ? floor : ceiling;
    }
}
