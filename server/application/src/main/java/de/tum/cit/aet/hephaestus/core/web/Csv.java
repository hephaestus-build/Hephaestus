package de.tum.cit.aet.hephaestus.core.web;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * RFC 4180 rows for the admin exports. Every cell is quoted, so a comma, a quote or a line break
 * inside a value never changes the column layout. A cell that starts with {@code = + - @ TAB CR} is
 * prefixed with an apostrophe: the cells carry user-written text and the reader is an administrator
 * opening the file in a spreadsheet, where such a value would otherwise run as a formula
 * (<a href="https://owasp.org/www-community/attacks/CSV_Injection">OWASP CSV injection</a>).
 */
public final class Csv {
    private Csv() {}

    public static void appendRow(StringBuilder out, List<@Nullable String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) out.append(',');
            out.append(cell(cells.get(i)));
        }
        out.append('\n');
    }

    private static String cell(@Nullable String value) {
        String text = value == null ? "" : value;
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) text = "'" + text;
        return '"' + text.replace("\"", "\"\"").replace("\r\n", "\n").replace('\r', '\n') + '"';
    }
}
