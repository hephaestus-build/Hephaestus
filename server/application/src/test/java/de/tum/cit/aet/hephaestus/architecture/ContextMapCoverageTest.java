package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.docs.Documenter;

/** The generated graph and the single relationship table must cover the same application modules. */
@Tag("architecture")
class ContextMapCoverageTest {
    private static final Path GUIDE = Path.of("../../docs/contributor/system-design.mdx");
    private static final Path OUTPUT = Path.of("build/spring-modulith-docs");
    private static final Pattern ROW = Pattern.compile("^\\| `(?<module>[^`]+)` \\|.+$", Pattern.MULTILINE);

    @Test
    void shouldDescribeEveryModuleInTheContextMapTable() throws Exception {
        String guide = Files.readString(GUIDE);
        int start = guide.indexOf("### Context map");
        assertThat(start)
                .as("The system design owns the context-map relationship table")
                .isNotNegative();
        int end = guide.indexOf("\n## ", start);
        String table = end < 0 ? guide.substring(start) : guide.substring(start, end);
        var declared = ROW.matcher(table).results().map(row -> row.group(1)).toList();
        var modules = ModulithVerificationTest.applicationModules().stream()
                .map(module -> module.getIdentifier().toString())
                .toList();
        assertThat(declared).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(modules);
    }

    @Test
    void shouldGenerateTheContextMapFromVerifiedModules() throws Exception {
        var modules = ModulithVerificationTest.applicationModules();
        modules.verify();
        Files.createDirectories(OUTPUT);
        var stale = OUTPUT.resolve("obsolete-module.puml");
        Files.writeString(stale, "A module that no longer exists");
        // Documenter's default clean removes stale modules before it writes this run's diagrams and canvases.
        new Documenter(modules, Documenter.Options.defaults().withOutputFolder(OUTPUT.toString())).writeDocumentation();
        assertThat(stale).doesNotExist();
        assertThat(OUTPUT.resolve("all-docs.adoc")).isRegularFile();
        try (var paths = Files.list(OUTPUT)) {
            assertThat(paths.map(path -> path.getFileName().toString()).toList())
                    .anyMatch(name -> name.endsWith(".puml"))
                    .anyMatch(name -> name.endsWith(".adoc") && !name.equals("all-docs.adoc"));
        }
    }
}
