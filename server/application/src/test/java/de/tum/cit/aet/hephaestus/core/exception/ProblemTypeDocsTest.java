package de.tum.cit.aet.hephaestus.core.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A problem's {@code type} URI ends in an anchor of the contributor guide, and the docs build refuses an address that
 * reaches no element. The pre-push hook runs this tier, so a new slug without its heading fails before CI does.
 */
@Tag("architecture")
class ProblemTypeDocsTest {

    @Test
    void shouldGiveEveryProblemTypeAHeadingInTheGuide() throws IOException {
        String guide = Files.readString(Path.of("../../docs/contributor/api-error-handling.mdx"));

        assertThat(Stream.of(ProblemType.values()).map(ProblemType::slug))
                .allSatisfy(slug -> assertThat(guide).contains("\n### " + slug + "\n"));
    }
}
