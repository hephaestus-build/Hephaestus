package de.tum.cit.aet.hephaestus.architecture;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The SQL that a Java source file can run: its string literals and text blocks outside Java comments, with literals
 * that {@code +} joins read as one. Each statement is normalized: SQL comments go, the contents of SQL string literals
 * are blanked, and white space collapses. SQL that the code assembles from values at run time stays invisible to it.
 */
final class SourceSql {
    // An unrolled loop: a repeated alternation recurses once per character and overflows on a long literal.
    private static final Pattern STRING = Pattern.compile("\"([^\"\\\\\\n]*+(?:\\\\.[^\"\\\\\\n]*+)*+)\"");
    private static final Pattern JOINED = Pattern.compile("\"\\s*\\+\\s*\"");
    private static final Pattern DOLLAR_QUOTE = Pattern.compile("\\$(?:[A-Za-z_]\\w*)?\\$");
    private static final Pattern QUERY_ANNOTATION = Pattern.compile("@(?:\\w+\\.)*(?:Query|NativeQuery)\\s*\\(");

    private SourceSql() {}

    static List<String> statements(String javaSource) {
        String source = withoutJavaComments(javaSource);
        var statements = new ArrayList<String>();
        var outsideBlocks = new StringBuilder();
        int index = 0;
        while (index < source.length()) {
            int literal = literalEnd(source, index);
            if (literal > index && source.startsWith("\"\"\"", index)) {
                statements.add(source.substring(index + 3, literal - 3).replace("\\\"", "\""));
                index = literal;
            } else if (literal > index) {
                outsideBlocks.append(source, index, literal);
                index = literal;
            } else {
                outsideBlocks.append(source.charAt(index));
                index++;
            }
        }
        Matcher strings = STRING.matcher(JOINED.matcher(outsideBlocks).replaceAll(""));
        while (strings.find()) statements.add(strings.group(1).replace("\\\"", "\""));
        return statements.stream().map(SourceSql::normalized).toList();
    }

    /**
     * The source without its query annotations, whose SQL the compiled class holds whole, so that a test that reads both
     * counts each annotation once. Only an annotation in Java code counts, never text in a literal.
     */
    static String withoutQueryAnnotations(String javaSource) {
        String source = withoutJavaComments(javaSource);
        var kept = new StringBuilder();
        int index = 0;
        while (index < source.length()) {
            int literal = literalEnd(source, index);
            if (literal > index) {
                kept.append(source, index, literal);
                index = literal;
                continue;
            }
            Matcher annotation = QUERY_ANNOTATION.matcher(source).region(index, source.length());
            if (source.charAt(index) == '@' && annotation.lookingAt()) {
                index = argumentsEnd(source, annotation.end());
                continue;
            }
            kept.append(source.charAt(index));
            index++;
        }
        return kept.toString();
    }

    /** The end of the argument list that opens before {@code start}, with literals inside it skipped whole. */
    private static int argumentsEnd(String source, int start) {
        int depth = 1;
        int index = start;
        while (index < source.length()) {
            int literal = literalEnd(source, index);
            if (literal > index) {
                index = literal;
                continue;
            }
            char next = source.charAt(index++);
            if (next == '(') depth++;
            if (next == ')' && --depth == 0) return index;
        }
        return source.length();
    }

    /**
     * The end of the Java string literal, text block or character literal that starts at {@code start}, or {@code start}
     * when none starts there.
     */
    private static int literalEnd(String source, int start) {
        if (source.startsWith("\"\"\"", start)) {
            // A text block ends at three quotes that no backslash escapes.
            int end = start + 3;
            while (end < source.length() && !source.startsWith("\"\"\"", end))
                end += source.charAt(end) == '\\' ? 2 : 1;
            return Math.min(end + 3, source.length());
        }
        char first = source.charAt(start);
        if (first != '"' && first != '\'') return start;
        int end = start + 1;
        while (end < source.length() && source.charAt(end) != first && source.charAt(end) != '\n')
            end += source.charAt(end) == '\\' ? 2 : 1;
        return Math.min(end + 1, source.length());
    }

    /**
     * The source without its Java comments, with string literals, text blocks and character literals kept whole. A
     * comment then neither hides SQL nor holds any.
     */
    static String withoutJavaComments(String source) {
        var kept = new StringBuilder();
        int index = 0;
        while (index < source.length()) {
            int literal = literalEnd(source, index);
            if (literal > index) {
                kept.append(source, index, literal);
                index = literal;
            } else if (source.startsWith("//", index)) {
                int end = source.indexOf('\n', index);
                index = end < 0 ? source.length() : end;
            } else if (source.startsWith("/*", index)) {
                int end = source.indexOf("*/", index + 2);
                index = end < 0 ? source.length() : end + 2;
                kept.append(' ');
            } else {
                kept.append(source.charAt(index));
                index++;
            }
        }
        return kept.toString();
    }

    /**
     * One SQL statement without comments, with the contents of its string literals blanked and its white space
     * collapsed, so the same query reads the same everywhere and a literal names no table.
     */
    static String normalized(String sql) {
        var kept = new StringBuilder();
        int index = 0;
        while (index < sql.length()) {
            Matcher dollar = DOLLAR_QUOTE.matcher(sql).region(index, sql.length());
            // A dollar quote starts a token: in revision$tag$ the dollar signs belong to the identifier.
            boolean tokenStart = index == 0 || !isIdentifierPart(sql.charAt(index - 1));
            if (sql.charAt(index) == '"') {
                int end = index + 1;
                while (end < sql.length()
                        && !(sql.charAt(end) == '"' && (end + 1 >= sql.length() || sql.charAt(end + 1) != '"')))
                    end += sql.charAt(end) == '"' ? 2 : 1;
                end = Math.min(end + 1, sql.length());
                kept.append(sql, index, end);
                index = end;
            } else if (sql.charAt(index) == '$' && tokenStart && dollar.lookingAt()) {
                int end = sql.indexOf(dollar.group(), dollar.end());
                kept.append("''");
                index = end < 0 ? sql.length() : end + dollar.group().length();
            } else if (sql.charAt(index) == '\'') {
                int end = index + 1;
                while (end < sql.length()
                        && !(sql.charAt(end) == '\'' && (end + 1 >= sql.length() || sql.charAt(end + 1) != '\'')))
                    end += sql.charAt(end) == '\'' ? 2 : 1;
                kept.append("''");
                index = Math.min(end + 1, sql.length());
            } else if (sql.startsWith("--", index)) {
                int end = sql.indexOf('\n', index);
                index = end < 0 ? sql.length() : end;
                kept.append(' ');
            } else if (sql.startsWith("/*", index)) {
                int depth = 0;
                while (index < sql.length()) {
                    if (sql.startsWith("/*", index)) {
                        depth++;
                        index += 2;
                    } else if (sql.startsWith("*/", index)) {
                        index += 2;
                        if (--depth == 0) break;
                    } else index++;
                }
                kept.append(' ');
            } else {
                kept.append(sql.charAt(index));
                index++;
            }
        }
        return kept.toString().replaceAll("\\s+", " ").strip();
    }

    private static boolean isIdentifierPart(char character) {
        return Character.isLetterOrDigit(character) || character == '_' || character == '$';
    }
}
