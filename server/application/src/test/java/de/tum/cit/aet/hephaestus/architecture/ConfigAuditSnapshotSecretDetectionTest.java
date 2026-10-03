package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditSnapshot;
import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Fixtures for {@link ConfigAuditSnapshotArchTest}'s secret detection. The rule guards an append-only
 * table, so a secret it fails to catch cannot be edited out afterwards.
 */
@Tag("architecture")
class ConfigAuditSnapshotSecretDetectionTest {

    record Gateway(String apiKey) {}

    record NestedSecretSnapshot(String name, Gateway gateway) implements ConfigAuditSnapshot {}

    record TopLevelSecretSnapshot(String apiKey) implements ConfigAuditSnapshot {}

    record PresenceFlagSnapshot(String modelName, boolean llmApiKeySet) implements ConfigAuditSnapshot {}

    static Stream<Arguments> snapshots() {
        return Stream.of(
                // Neither the component name (`gateway`) nor the record name trips the deny-list, so only
                // the recursion can catch this — the snapshot is serialized whole.
                Arguments.of(NestedSecretSnapshot.class, "gateway.apiKey", "a secret one level down"),
                Arguments.of(TopLevelSecretSnapshot.class, "apiKey", "a secret on the snapshot itself"),
                // Recording that a key exists is the sanctioned alternative to recording the key.
                Arguments.of(PresenceFlagSnapshot.class, null, "a presence flag is allowed"));
    }

    @ParameterizedTest(name = "{2}")
    @MethodSource("snapshots")
    void detectsSecretLikeComponents(Class<?> snapshot, @Nullable String expectedComponent, String why) {
        List<String> violations = violationsFor(snapshot);

        if (expectedComponent == null) {
            assertThat(violations).as(why).isEmpty();
        } else {
            assertThat(violations)
                    .as(why)
                    .anySatisfy(violation -> assertThat(violation).contains(expectedComponent));
        }
    }

    private static List<String> violationsFor(Class<?> snapshot) {
        var imported = new ClassFileImporter().importClasses(snapshot, Gateway.class);
        EvaluationResult result =
                ConfigAuditSnapshotArchTest.secretLikeComponentRule().evaluate(imported);
        return result.getFailureReport().getDetails();
    }
}
