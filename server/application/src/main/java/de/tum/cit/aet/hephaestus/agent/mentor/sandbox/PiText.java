package de.tum.cit.aet.hephaestus.agent.mentor.sandbox;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The text that Pi 1.0's {@code read} and {@code bash} tools give the model, made from what a sandbox command returns.
 * The sandbox sends at most one page, so this server and the Heph host never hold a whole file or a whole output. The
 * limits, the notices and their wording are Pi's ({@code pi-coding-agent/dist/core/tools/truncate.js}, {@code read.js},
 * {@code bash.js}): the model was trained on them.
 */
final class PiText {

    static final int MAX_LINES = 2000;
    static final int MAX_BYTES = 50 * 1024;

    private PiText() {}

    /** Pi's {@code formatSize}. */
    static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1fKB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1fMB", bytes / (1024.0 * 1024.0));
    }

    /** The text of one page, or the error that Pi's {@code read} throws. */
    record Page(@Nullable String text, @Nullable String offsetError) {}

    /**
     * Pi's {@code read} of a text file, from what the sandbox read of it for one page.
     *
     * @param path the path as the model wrote it, for the notice
     * @param offset Pi's 1-based first line, or null
     * @param limit Pi's line limit, or null
     * @param newlines the number of newline bytes in the whole file
     * @param firstLineBytes the length of the page's first line in bytes, without its newline
     * @param chunk the file from the page's first line on: at most {@link #MAX_LINES} + 1 lines and at most
     *     {@link #MAX_BYTES} + 1 bytes of them
     */
    static Page read(
            String path,
            @Nullable Integer offset,
            @Nullable Integer limit,
            long newlines,
            long firstLineBytes,
            byte[] chunk) {
        long totalLines = newlines + 1;
        long startLine = offset == null ? 0 : Math.max(0, offset - 1L);
        if (startLine >= totalLines) {
            return new Page(null, "Offset " + offset + " is beyond end of file (" + totalLines + " lines total)");
        }
        long wanted = limit == null ? totalLines - startLine : Math.min(limit, totalLines - startLine);
        boolean toEnd = startLine + wanted >= totalLines;
        boolean whole;
        String selected = new String(chunk, StandardCharsets.UTF_8);
        if (toEnd) {
            whole = chunk.length <= MAX_BYTES && wanted <= MAX_LINES + 1L;
        } else {
            // The sandbox sends whole lines with their newlines; it sent the whole selection when it holds every one.
            whole = wanted <= MAX_LINES + 1L && newlines(chunk) == wanted && selected.endsWith("\n");
            if (whole) {
                // Pi joins the selected lines without the newline of the last one, before it measures them.
                selected = selected.substring(0, selected.length() - 1);
            }
        }
        List<String> lines = splitForCounting(selected);
        if (!whole && chunk.length > MAX_BYTES && !lines.isEmpty() && !selected.endsWith("\n")) {
            // The byte bound cut the last line: it is incomplete, and a page never holds part of a line.
            lines.remove(lines.size() - 1);
        }
        long startDisplay = startLine + 1;
        boolean fits = whole && lines.size() <= MAX_LINES && utf8Length(selected) <= MAX_BYTES;
        if (fits) {
            if (limit != null && !toEnd) {
                long next = startLine + wanted + 1;
                return new Page(
                        selected + "\n\n[" + (totalLines - startLine - wanted) + " more lines in file. Use offset="
                                + next + " to continue.]",
                        null);
            }
            return new Page(selected, null);
        }
        if (firstLineBytes > MAX_BYTES) {
            return new Page(
                    "[Line " + startDisplay + " is " + formatSize(firstLineBytes) + ", exceeds "
                            + formatSize(MAX_BYTES) + " limit. Use bash: sed -n '" + startDisplay + "p' " + path
                            + " | head -c " + MAX_BYTES + "]",
                    null);
        }
        List<String> shown = new ArrayList<>();
        long bytes = 0;
        boolean byBytes = false;
        for (String line : lines) {
            if (shown.size() >= MAX_LINES) {
                break;
            }
            long lineBytes = utf8Length(line) + (shown.isEmpty() ? 0 : 1);
            if (bytes + lineBytes > MAX_BYTES) {
                byBytes = true;
                break;
            }
            shown.add(line);
            bytes += lineBytes;
        }
        if (!byBytes && chunk.length > MAX_BYTES && shown.size() == lines.size()) {
            // The sandbox sent no more because of the byte bound: the next line would not have fit either.
            byBytes = true;
        }
        if (shown.size() >= MAX_LINES && bytes <= MAX_BYTES) {
            byBytes = false;
        }
        long endDisplay = startDisplay + shown.size() - 1;
        String notice = byBytes
                ? "[Showing lines " + startDisplay + "-" + endDisplay + " of " + totalLines + " ("
                        + formatSize(MAX_BYTES) + " limit). Use offset=" + (endDisplay + 1) + " to continue.]"
                : "[Showing lines " + startDisplay + "-" + endDisplay + " of " + totalLines + ". Use offset="
                        + (endDisplay + 1) + " to continue.]";
        return new Page(String.join("\n", shown) + "\n\n" + notice, null);
    }

    /** Pi's {@code bash} output text, and whether it was cut. */
    record Tail(String text, boolean truncated) {}

    /**
     * Pi's {@code bash} tail: the last {@link #MAX_LINES} lines or {@link #MAX_BYTES}, with Pi's notice when cut.
     *
     * @param newlines the number of newline bytes in the whole output
     * @param totalBytes the length of the whole output
     * @param lastLineBytes the length of the last line in bytes, without its newline
     * @param tail the last {@link #MAX_BYTES} + 2 bytes of the output, or all of it when shorter
     */
    static Tail bash(long newlines, long totalBytes, long lastLineBytes, byte[] tail, String fullOutputPath) {
        String text = new String(tail, StandardCharsets.UTF_8);
        boolean complete = tail.length >= totalBytes;
        boolean endsWithNewline = tail.length > 0 && tail[tail.length - 1] == '\n';
        long totalLines = newlines + (totalBytes > 0 && !endsWithNewline ? 1 : 0);
        if (complete && totalLines <= MAX_LINES && totalBytes <= MAX_BYTES) {
            return new Tail(text, false);
        }
        List<String> lines = splitForCounting(text);
        if (!complete && !lines.isEmpty() && lines.size() > 1) {
            // The first line of the tail may begin before it: it is incomplete.
            lines.remove(0);
        }
        List<String> shown = new ArrayList<>();
        long bytes = 0;
        boolean byBytes = false;
        boolean partial = false;
        for (int index = lines.size() - 1; index >= 0 && shown.size() < MAX_LINES; index--) {
            String line = lines.get(index);
            long lineBytes = utf8Length(line) + (shown.isEmpty() ? 0 : 1);
            if (bytes + lineBytes > MAX_BYTES) {
                byBytes = true;
                if (shown.isEmpty()) {
                    String end = lastBytes(line, MAX_BYTES);
                    shown.add(0, end);
                    bytes = utf8Length(end);
                    partial = true;
                }
                break;
            }
            shown.add(0, line);
            bytes += lineBytes;
        }
        if (!byBytes && !complete && shown.size() == lines.size()) {
            // The tail held no more because of the byte bound: the line before it would not have fit either.
            byBytes = true;
        }
        if (shown.size() >= MAX_LINES && bytes <= MAX_BYTES) {
            byBytes = false;
        }
        String content = String.join("\n", shown);
        long startLine = totalLines - shown.size() + 1;
        String notice;
        if (partial) {
            notice = "[Showing last " + formatSize(utf8Length(content)) + " of line " + totalLines + " (line is "
                    + formatSize(lastLineBytes) + "). Full output: " + fullOutputPath + "]";
        } else if (byBytes) {
            notice = "[Showing lines " + startLine + "-" + totalLines + " of " + totalLines + " ("
                    + formatSize(MAX_BYTES) + " limit). Full output: " + fullOutputPath + "]";
        } else {
            notice = "[Showing lines " + startLine + "-" + totalLines + " of " + totalLines + ". Full output: "
                    + fullOutputPath + "]";
        }
        return new Tail(content + "\n\n" + notice, true);
    }

    private static long newlines(byte[] bytes) {
        long count = 0;
        for (byte b : bytes) {
            if (b == '\n') count++;
        }
        return count;
    }

    /** Pi's {@code splitLinesForCounting}: a final newline ends the last line and starts none. */
    private static List<String> splitForCounting(String content) {
        List<String> lines = new ArrayList<>();
        if (content.isEmpty()) {
            return lines;
        }
        int from = 0;
        for (int at = content.indexOf('\n'); at >= 0; at = content.indexOf('\n', from)) {
            lines.add(content.substring(from, at));
            from = at + 1;
        }
        if (from < content.length()) {
            lines.add(content.substring(from));
        }
        return lines;
    }

    /** The end of {@code line} that fits in {@code limit} UTF-8 bytes, without a split character. */
    private static String lastBytes(String line, int limit) {
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        int from = Math.max(0, bytes.length - limit);
        while (from < bytes.length && (bytes[from] & 0xC0) == 0x80) {
            from++;
        }
        return new String(bytes, from, bytes.length - from, StandardCharsets.UTF_8);
    }

    static long utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
