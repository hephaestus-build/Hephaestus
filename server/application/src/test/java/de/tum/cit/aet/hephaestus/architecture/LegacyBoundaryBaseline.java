package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The module-boundary violations that main had before ADR 0138, one per line, in
 * {@code src/test/resources/architecture/<name>.txt}. A new violation fails, and so does a listed one that no longer
 * occurs, so the list only shrinks. An edge that touches a mentoring module never gets an entry.
 */
final class LegacyBoundaryBaseline {
    private static final Path DIRECTORY = Path.of("src/test/resources/architecture");

    private LegacyBoundaryBaseline() {}

    static void assertMatches(String name, Collection<String> violations) {
        var listed = listed(name);
        var actual = new TreeSet<>(violations);
        var added = new TreeSet<>(actual);
        added.removeAll(listed);
        var fixed = new TreeSet<>(listed);
        fixed.removeAll(actual);
        assertThat(added)
                .as("New module-boundary violations. Go through the owner's interface instead (%s)", name)
                .isEmpty();
        assertThat(fixed)
                .as("Fixed violations that %s.txt still lists. Remove these lines", name)
                .isEmpty();
    }

    private static Set<String> listed(String name) {
        try {
            List<String> lines = Files.readAllLines(DIRECTORY.resolve(name + ".txt"));
            var listed = new TreeSet<String>();
            for (String line : lines) if (!line.isBlank() && !line.startsWith("#")) listed.add(line.strip());
            return listed;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
