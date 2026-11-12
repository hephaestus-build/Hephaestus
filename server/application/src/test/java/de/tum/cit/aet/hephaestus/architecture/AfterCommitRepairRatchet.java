package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.function.Consumer;

final class AfterCommitRepairRatchet {
    private AfterCommitRepairRatchet() {}

    static void check(String version, Map<String, ?> unrepaired, Consumer<String> report) {
        String summary = "After-commit repair ratchet for " + version + ": " + unrepaired;
        report.accept(summary);
        // Build metadata can contain a hyphen without making the version a pre-release.
        if (!version.split("\\+", 2)[0].contains("-")) {
            assertThat(unrepaired)
                    .as("Release requires every after-commit effect to have a repair; %s", summary)
                    .isEmpty();
        }
    }
}
