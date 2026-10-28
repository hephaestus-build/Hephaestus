package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ScanDefinitionTest extends BaseUnitTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "/etc/passwd",
                "../secret",
                "src/./file.ts",
                ".git/config",
                "src\\file.ts",
                "src/**/*.{ts,tsx}",
                "C:/secret",
                "src//file.ts"
            })
    void shouldRejectPathsOutsideTheRelativeAntGrammar(String path) {
        assertThatThrownBy(() ->
                        new ScanDefinition(new ItemKind("scm.file"), List.of(path), List.of(), List.of(), reads()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldKeepJudgmentAndContextGrantsSeparateAndCanonical() {
        var scan = new ScanDefinition(
                new ItemKind("scm.file"), List.of("**/*.tsx", "**/*.ts"), List.of("dist/**"), List.of("**/*"), reads());
        assertThat(scan.include()).containsExactly("**/*.ts", "**/*.tsx");
        assertThat(scan.exclude()).containsExactly("dist/**");
        assertThat(scan.context()).containsExactly("**/*");
    }

    @Test
    void shouldRequireExplicitJudgmentTargetsAndRejectDuplicateGlobs() {
        assertThatThrownBy(() ->
                        new ScanDefinition(new ItemKind("scm.file"), List.of(), List.of(), List.of("**/*"), reads()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScanDefinition(
                        new ItemKind("scm.file"), List.of("**/*.ts", "**/*.ts"), List.of(), List.of(), reads()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldUseSafeGlobsForChangedPathsAndLiteralDiffText() {
        assertThatThrownBy(() -> new AppliesWhenClause(List.of("../secret"), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new AppliesWhenClause(null, List.of(".*(password|token).*"), null).diffContains())
                .containsExactly(".*(password|token).*");
    }

    @Test
    void shouldRejectAScanThatOmitsItsOwnReadRequirements() {
        var mapper = JsonMapper.builder().build();
        var encoded = (ObjectNode) mapper.valueToTree(
                new ScanDefinition(new ItemKind("scm.file"), List.of("**/*.ts"), List.of(), List.of("**/*"), reads()));
        encoded.remove("reads");
        assertThatThrownBy(() -> mapper.treeToValue(encoded, ScanDefinition.class))
                .isInstanceOf(JacksonException.class);
    }

    private static PracticeReads reads() {
        return new PracticeReads(
                "3.0.0", List.of(new PracticeReadSource(new SourceKind("scm.repository.tree"), ReadNeed.COMPLETE)));
    }
}
