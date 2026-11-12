package de.tum.cit.aet.hephaestus.agent.practice;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.Severity;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class PracticeScriptContractSyncTest extends BaseUnitTest {
    private static String contract() throws IOException {
        for (Path root = Path.of("").toAbsolutePath(); root != null; root = root.getParent()) {
            Path file = root.resolve("docker/agents/practice-scripts/lib/core/contract.ts");
            if (Files.isRegularFile(file)) return Files.readString(file);
        }
        throw new IOException("The practice script core contract was not found");
    }

    private static List<String> values(String contract, String name) {
        var array = Pattern.compile("\\b" + name + "\\s*=\\s*\\[([^]]*)]", Pattern.DOTALL)
                .matcher(contract);
        if (!array.find()) throw new IllegalArgumentException("The core contract has no array named " + name);
        return Pattern.compile("\"([^\"]+)\"")
                .matcher(array.group(1))
                .results()
                .map(match -> match.group(1))
                .toList();
    }

    @Test
    void shouldUseTheSameClosedOutcomesSeveritiesOriginsAndRoles() throws IOException {
        var source = contract();
        assertThat(values(source, "OUTCOMES"))
                .containsExactlyElementsOf(
                        Arrays.stream(ResultOutcome.values()).map(Enum::name).toList());
        assertThat(values(source, "SEVERITIES"))
                .containsExactlyElementsOf(
                        Arrays.stream(Severity.values()).map(Enum::name).toList());
        assertThat(values(source, "ORIGINS"))
                .containsExactlyElementsOf(
                        Arrays.stream(ReviewOrigin.values()).map(Enum::name).toList());
        assertThat(values(source, "ABOUT_ROLES"))
                .containsExactlyElementsOf(
                        Arrays.stream(AboutRole.values()).map(Enum::name).toList());
    }

    @Test
    void shouldUseTheSameContractVersionAndPracticeSlug() throws IOException {
        var source = contract();
        assertThat(source).contains("CONTRACT_VERSION = 3");
        assertThat(source).contains("PRACTICE_SLUG = /^" + SandboxLayout.PRACTICE_SLUG.pattern() + "$/u");
    }
}
