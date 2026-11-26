package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A review item's copied identities live only in {@code review_item_copied_identity}. The migration moved every list
 * out of {@code review_item.detail.custody}, so no code reads {@code copiedIdentities} from a JSON detail: neither
 * a SQL JSON path nor a Jackson read.
 */
class CopiedIdentitiesLeftTheDetailTest extends HephaestusArchitectureTest {

    /** A SQL JSON path, or a Jackson read, of the old list. Writing the record type's field is no read. */
    private static final Pattern DETAIL_READ =
            Pattern.compile("'copiedIdentities'|\\.(path|get|has|at)\\(\\s*\"/?copiedIdentities\"");

    @Test
    void shouldMatchReadsAndIgnoreTheRecordsField() {
        assertThat(DETAIL_READ
                        .matcher("member.detail->'custody'->'copiedIdentities'")
                        .find())
                .isTrue();
        assertThat(DETAIL_READ
                        .matcher("detail.path(\"custody\").path(\"copiedIdentities\")")
                        .find())
                .isTrue();
        assertThat(DETAIL_READ.matcher("custody.copiedIdentities().stream()").find())
                .isFalse();
    }

    @Test
    void shouldReadNoCopiedIdentitiesFromAReviewItemsDetail() {
        Path sources = Path.of("src/main/java");
        if (!Files.isDirectory(sources))
            fail("Could not locate the main sources at %s (cwd=%s)"
                    .formatted(sources, Path.of("").toAbsolutePath()));
        List<String> readers;
        try (Stream<Path> paths = Files.walk(sources)) {
            readers = paths.filter(path -> path.toString().endsWith(".java"))
                    .filter(CopiedIdentitiesLeftTheDetailTest::readsTheDetailList)
                    .map(Path::toString)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(readers)
                .as("read copied identities from review_item_copied_identity through ReviewItemCustody")
                .isEmpty();
    }

    private static boolean readsTheDetailList(Path source) {
        try {
            return DETAIL_READ.matcher(Files.readString(source)).find();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
