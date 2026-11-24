package de.tum.cit.aet.hephaestus.architecture;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The SQL that a Java source file can run: its string literals and text blocks outside Java comments, with literals
 * that {@code +} joins read as one, and SQL comments removed. SQL that the code assembles from values at run time stays invisible to it.
 */
final class SourceSql {
    private static final Pattern TEXT_BLOCK = Pattern.compile("\"\"\"(.*?)\"\"\"", Pattern.DOTALL);
    // An unrolled loop: a repeated alternation recurses once per character and overflows on a long literal.
    private static final Pattern STRING = Pattern.compile("\"([^\"\\\\\\n]*+(?:\\\\.[^\"\\\\\\n]*+)*+)\"");
    private static final Pattern JOINED = Pattern.compile("\"\\s*\\+\\s*\"");
    private static final Pattern SQL_COMMENT = Pattern.compile("--[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

    private static final Pattern QUERY_ANNOTATION = Pattern.compile("@(?:\\w+\\.)*(?:Query|NativeQuery)\\s*\\(");

    private SourceSql() {}

    /**
     * The source without its query annotations, whose SQL the compiled class holds whole, so that a test that reads both
     * counts each annotation once.
     */
    static String withoutQueryAnnotations(String javaSource) {
        String source = withoutJavaComments(javaSource);
        var kept = new StringBuilder();
        int from = 0;
        var annotation = QUERY_ANNOTATION.matcher(source);
        while (annotation.find(from)) {
            kept.append(source, from, annotation.start());
            int index = annotation.end();
            int depth = 1;
            while (index < source.length() && depth > 0) {
                if (source.startsWith("\"\"\"", index)) {
                    int end = source.indexOf("\"\"\"", index + 3);
                    index = end < 0 ? source.length() : end + 3;
                    continue;
                }
                char next = source.charAt(index);
                if (next == '"') {
                    index++;
                    while (index < source.length() && source.charAt(index) != '"')
                        index += source.charAt(index) == '\\' ? 2 : 1;
                } else if (next == '(') depth++;
                else if (next == ')') depth--;
                index++;
            }
            from = index;
        }
        return kept.append(source.substring(Math.min(from, source.length()))).toString();
    }

    static List<String> statements(String javaSource) {
        String source = withoutJavaComments(javaSource);
        var statements = new ArrayList<String>();
        Matcher blocks = TEXT_BLOCK.matcher(source);
        while (blocks.find()) statements.add(blocks.group(1));
        Matcher strings = STRING.matcher(
                JOINED.matcher(TEXT_BLOCK.matcher(source).replaceAll("")).replaceAll(""));
        while (strings.find()) statements.add(strings.group(1).replace("\\\"", "\""));
        return statements.stream().map(SourceSql::normalized).toList();
    }

    /**
     * The source without its Java comments, with string literals, text blocks and character literals kept whole. A
     * comment then neither hides SQL nor holds any.
     */
    static String withoutJavaComments(String source) {
        var kept = new StringBuilder();
        int index = 0;
        while (index < source.length()) {
            if (source.startsWith("//", index)) {
                int end = source.indexOf('\n', index);
                index = end < 0 ? source.length() : end;
            } else if (source.startsWith("/*", index)) {
                int end = source.indexOf("*/", index + 2);
                index = end < 0 ? source.length() : end + 2;
                kept.append(' ');
            } else if (source.startsWith("\"\"\"", index)) {
                int end = source.indexOf("\"\"\"", index + 3);
                int stop = end < 0 ? source.length() : end + 3;
                kept.append(source, index, stop);
                index = stop;
            } else if (source.charAt(index) == '"' || source.charAt(index) == '\'') {
                char quote = source.charAt(index);
                int end = index + 1;
                while (end < source.length() && source.charAt(end) != quote && source.charAt(end) != '\n')
                    end += source.charAt(end) == '\\' ? 2 : 1;
                int stop = Math.min(end + 1, source.length());
                kept.append(source, index, stop);
                index = stop;
            } else {
                kept.append(source.charAt(index));
                index++;
            }
        }
        return kept.toString();
    }

    /** One SQL statement without comments, its white space collapsed, so the same query reads the same everywhere. */
    static String normalized(String sql) {
        return SQL_COMMENT.matcher(sql).replaceAll(" ").replaceAll("\\s+", " ").strip();
    }
}
