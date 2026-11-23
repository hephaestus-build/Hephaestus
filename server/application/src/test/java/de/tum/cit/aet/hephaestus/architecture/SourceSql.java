package de.tum.cit.aet.hephaestus.architecture;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The SQL that a Java source file can run: its string literals and text blocks, with literals that {@code +} joins read
 * as one, and SQL comments removed. SQL that the code assembles from values at run time stays invisible to it.
 */
final class SourceSql {
    private static final Pattern TEXT_BLOCK = Pattern.compile("\"\"\"(.*?)\"\"\"", Pattern.DOTALL);
    private static final Pattern STRING = Pattern.compile("\"((?:\\\\.|[^\"\\\\\\n])*)\"");
    private static final Pattern JOINED = Pattern.compile("\"\\s*\\+\\s*\"");
    private static final Pattern SQL_COMMENT = Pattern.compile("--[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

    private SourceSql() {}

    static List<String> statements(String source) {
        var statements = new ArrayList<String>();
        Matcher blocks = TEXT_BLOCK.matcher(source);
        while (blocks.find()) statements.add(blocks.group(1));
        Matcher strings = STRING.matcher(
                JOINED.matcher(TEXT_BLOCK.matcher(source).replaceAll("")).replaceAll(""));
        while (strings.find()) statements.add(strings.group(1).replace("\\\"", "\""));
        return statements.stream()
                .map(sql -> SQL_COMMENT.matcher(sql).replaceAll(" "))
                .toList();
    }
}
