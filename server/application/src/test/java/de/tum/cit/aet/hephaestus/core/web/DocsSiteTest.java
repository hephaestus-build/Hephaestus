package de.tum.cit.aet.hephaestus.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("unit")
class DocsSiteTest {

    @ParameterizedTest
    @CsvSource({
        "1.0.0, https://docs.hephaestus.build/admin/upgrade",
        "1.0.0-rc.8, https://docs.hephaestus.build/admin/upgrade",
        "0.0.0-development, https://docs.hephaestus.build/next/admin/upgrade",
        "1.0.0-rc.8-12-gabcdef, https://docs.hephaestus.build/next/admin/upgrade",
        "'', https://docs.hephaestus.build/next/admin/upgrade"
    })
    void shouldLinkAReleaseToTheReleaseDocsAndEveryOtherBuildToMain(String version, String expected) {
        assertThat(DocsSite.page(version, "admin/upgrade")).isEqualTo(expected);
    }

    /**
     * A docs link follows the build's docs site, so only this class names the site's root. A problem type in an
     * OpenAPI description is an identifier: it names the release docs on purpose (ADR 0103 rule 5).
     */
    @Test
    void shouldNameTheDocsRootOnlyInThisClass() throws IOException {
        Path main = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(main)) {
            List<String> offenders = files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> !file.endsWith("core/web/DocsSite.java"))
                    .filter(file -> {
                        try {
                            return Files.readString(file)
                                    .replace(DocsSite.ROOT + "contributor/api-error-handling#", "")
                                    .contains(DocsSite.ROOT);
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .map(Path::toString)
                    .toList();
            assertThat(offenders).isEmpty();
        }
    }
}
