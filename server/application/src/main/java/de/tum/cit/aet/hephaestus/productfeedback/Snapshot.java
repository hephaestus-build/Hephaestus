package de.tum.cit.aet.hephaestus.productfeedback;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The public repository's open issues, stored as {@code number<TAB>title} lines, newest update first. */
final class Snapshot {
    private Snapshot() {}

    static final int LIMIT = 500;

    record Entry(long number, String title) {}

    static String write(List<Entry> entries) {
        var out = new StringBuilder();
        for (Entry entry : entries.subList(0, Math.min(LIMIT, entries.size())))
            out.append(entry.number())
                    .append('\t')
                    .append(entry.title().replace('\n', ' ').replace('\t', ' '))
                    .append('\n');
        return out.toString();
    }

    static List<Entry> read(@Nullable String text) {
        var entries = new ArrayList<Entry>();
        if (text == null) return entries;
        for (String line : text.lines().toList()) {
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            try {
                entries.add(new Entry(Long.parseLong(line.substring(0, tab)), line.substring(tab + 1)));
            } catch (NumberFormatException ignored) {
                // A line that is not "number<TAB>title" is not an entry.
            }
        }
        return entries;
    }
}
