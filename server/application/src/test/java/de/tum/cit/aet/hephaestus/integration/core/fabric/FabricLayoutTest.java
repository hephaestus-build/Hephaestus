package de.tum.cit.aet.hephaestus.integration.core.fabric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class FabricLayoutTest extends BaseUnitTest {

    @TempDir
    Path root;

    private FabricLayout layout() {
        return new FabricLayout(root.toString());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "..", ".", "a/b", "a\\b", "../etc", "..\\win"})
    void source_rejectsUnsafeConnectorId(String unsafe) {
        assertThatThrownBy(() -> layout().source(unsafe, "ok")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "..", ".", "a/b", "a\\b"})
    void source_rejectsUnsafeExternalId(String unsafe) {
        assertThatThrownBy(() -> layout().source("scm", unsafe)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void source_dottedConnectorIdIsLegal_resolvesUnderSources() {
        // The contract is "dots allowed, separators/traversal rejected": a dotted connector id like
        // scm.gitlab — and an externalId that merely contains a double dot like v1..2 — are NOT traversal.
        Path resolved = layout().source("scm.gitlab", "v1..2");

        assertThat(resolved)
                .isEqualTo(root.resolve("sources").resolve("scm.gitlab").resolve("v1..2"));
        assertThat(resolved.startsWith(root)).isTrue();
    }

    @Test
    void source_legalNumericId_resolvesUnderSourcesScm() {
        assertThat(layout().source("scm", "42"))
                .isEqualTo(root.resolve("sources").resolve("scm").resolve("42"));
    }
}
