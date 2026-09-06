package de.tum.cit.aet.hephaestus.testconfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The values a Liquibase {@code CHECK ... IN (…)} constraint admits, read from the changelogs.
 *
 * <p>The suite builds its schema with {@code ddl-auto: create} and applies no changelog, so a Java
 * enum that has outgrown the constraint pinning its column is invisible to every other tier: the test
 * passes and the production insert is rejected. A parity test per constrained column is what closes
 * that, and this is the reading half they share.
 */
public final class LiquibaseCheckConstraints {

    private static final Path MASTER = Path.of("src/main/resources/db/master.xml");

    private static final Pattern INCLUDE = Pattern.compile("<include\\s+file=\"([^\"]+)\"");

    /** Not just {@code [A-Z_]}: a constrained column may hold lower-case or dotted values. */
    private static final Pattern QUOTED = Pattern.compile("'([^']+)'");

    /**
     * A rollback restates the definition it undoes, and an update never applies one. The empty form
     * closes itself, and it has to be matched first: read only as an opening tag it would swallow every
     * changeset between one of those and the next real {@code </rollback>}.
     */
    private static final Pattern ROLLBACK =
            Pattern.compile("<rollback\\b[^>]*/>|<rollback\\b[^>]*>.*?</rollback>", Pattern.DOTALL);

    private LiquibaseCheckConstraints() {}

    /**
     * Walks {@code master.xml}'s include list in reverse — the order Liquibase actually applies, which
     * the filename order is only conventionally aligned with — and returns the values the last
     * definition of {@code constraintName} admits. A changelog on disk but absent from
     * {@code master.xml} therefore cannot satisfy a caller, because it never reaches a database either.
     *
     * @return the admitted values, or an empty set when no changelog defines the constraint
     */
    public static Set<String> admittedValues(String constraintName, String columnName) throws IOException {
        // ADD is optional: a constraint may be named inside the CREATE TABLE that introduced its column
        // rather than added afterwards, and both spellings reach a database the same way.
        Pattern check = Pattern.compile(
                "(?:ADD\\s+)?CONSTRAINT\\s+" + Pattern.quote(constraintName) + "\\s+CHECK\\s*\\(\\s*"
                        + Pattern.quote(columnName)
                        + "\\s+IN\\s*\\((.*?)\\)\\s*\\)",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        String master = Files.readString(MASTER, StandardCharsets.UTF_8);
        Matcher include = INCLUDE.matcher(master);
        List<String> included = new ArrayList<>();
        while (include.find()) {
            included.add(include.group(1));
        }
        Path changelogDirectory = MASTER.getParent();
        if (changelogDirectory == null) {
            return Set.of();
        }
        for (String changelog : included.reversed()) {
            Path path = changelogDirectory.resolve(changelog).normalize();
            if (!Files.exists(path)) {
                continue;
            }
            String applied = ROLLBACK.matcher(Files.readString(path, StandardCharsets.UTF_8))
                    .replaceAll("");
            Matcher constraint = check.matcher(applied);
            String lastInFile = null;
            while (constraint.find()) {
                lastInFile = constraint.group(1);
            }
            if (lastInFile != null) {
                Set<String> values = new LinkedHashSet<>();
                Matcher value = QUOTED.matcher(lastInFile);
                while (value.find()) {
                    values.add(value.group(1));
                }
                return values;
            }
        }
        return Set.of();
    }
}
