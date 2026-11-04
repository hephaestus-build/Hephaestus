package de.tum.cit.aet.hephaestus.practices.result;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * These reasons describe rows that a release before 1.0 left. The rows stay and keep rendering, but a new row with one
 * would say that something was not recorded when it could have been. So each value is named only where it is declared,
 * and where custody reads it: not as a constant, and not as a string in SQL. The compiler inlines a string constant, so
 * this reads the source and not the bytecode. A runtime stop reason comes from an allowlist
 * ({@code RuntimeNonResultDTO}) that holds none of them.
 */
class HistoryOnlyValuesTest extends BaseUnitTest {

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "LEGACY_FAILURE", Set.of("practices/spi/ReviewStopReason.java"),
            "LEGACY_SCOPE_UNKNOWN", Set.of("practices/spi/ReviewStopReason.java"),
            "LEGACY_REVIEW_UNBOUND", Set.of("integration/core/spi/ReviewRequestReason.java"),
            "LEGACY_AMBIGUOUS_RESULTS",
                    Set.of("practices/review/model/ReviewItem.java", "practices/result/ResultSourceAuthorization.java"),
            "LEGACY_REUSE_UNBOUND", Set.of());

    @Test
    void shouldNameEachValueOnlyWhereItIsDeclaredOrReadWhenCodeIsScanned() throws IOException {
        Path root = sources();
        Map<String, Set<String>> found = new TreeMap<>();
        ALLOWED.keySet().forEach(value -> found.put(value, new TreeSet<>()));
        try (var files = Files.walk(root)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                for (String value : ALLOWED.keySet()) {
                    if (source.contains(value)) {
                        Objects.requireNonNull(found.get(value))
                                .add(root.relativize(file).toString().replace('\\', '/'));
                    }
                }
            }
        }
        assertThat(found).containsExactlyInAnyOrderEntriesOf(ALLOWED);
    }

    private static Path sources() {
        Path module = Path.of("src/main/java/de/tum/cit/aet/hephaestus");
        return Files.isDirectory(module)
                ? module
                : Path.of("server/application/src/main/java/de/tum/cit/aet/hephaestus");
    }
}
