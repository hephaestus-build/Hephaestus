package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class LogSecretLiteralArchTest {

    private static final Path SOURCES = Path.of("src/main/java/de/tum/cit/aet/hephaestus");
    private static final Pattern RAW_BEARER_LITERAL = Pattern.compile("\\\"[^\\\"]*Bearer[^\\\"]*\\\"");

    @Test
    void logStatementsNeverContainRawBearerLiterals() throws IOException {
        List<Path> violations = new ArrayList<>();
        try (var files = Files.walk(SOURCES)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                for (String statement : Files.readString(file).split(";", -1)) {
                    int logCall = Math.max(statement.lastIndexOf("log."), statement.lastIndexOf(".log("));
                    if (logCall >= 0
                            && RAW_BEARER_LITERAL
                                    .matcher(statement.substring(logCall))
                                    .find()) {
                        violations.add(file);
                        break;
                    }
                }
            }
        }
        assertThat(violations)
                .as("raw Bearer literals in logs bypass the structured-log redaction contract")
                .isEmpty();
    }
}
