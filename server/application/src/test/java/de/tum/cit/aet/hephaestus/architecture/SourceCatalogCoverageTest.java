package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceContract;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("architecture")
class SourceCatalogCoverageTest {

    private static final Set<SourceKind> CATALOG_KINDS = new ClasspathSourceCatalogRegistry(
                    JsonMapper.builder().build(), Clock.systemUTC())
            .current().sources().stream().map(SourceContract::kind).collect(Collectors.toUnmodifiableSet());

    private static final Set<SourceKind> COLLECTED_KINDS = collectedKinds();

    private static Set<SourceKind> collectedKinds() {
        // The tier's shared import, tests included. A separate import of these packages would sit in the one
        // architecture JVM next to the shared ones.
        return HephaestusArchitectureTest.classesWithTests
                .that(resideInAnyPackage(
                        "de.tum.cit.aet.hephaestus.integration..", "de.tum.cit.aet.hephaestus.agent.context.."))
                .stream()
                .map(JavaClass::reflect)
                .filter(type -> !type.isInterface() && !Modifier.isAbstract(type.getModifiers()))
                .flatMap(type -> declaredKinds(type).stream())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<SourceKind> declaredKinds(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .filter(field -> SourceKind.class.equals(field.getType()))
                .map(SourceCatalogCoverageTest::read)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static SourceKind read(Field field) {
        try {
            field.setAccessible(true);
            return (SourceKind) field.get(null);
        } catch (IllegalAccessException exception) {
            throw new AssertionError("Cannot read " + field, exception);
        }
    }

    @Test
    @DisplayName("every collected source kind is governed by the catalog")
    void everyCollectedKindIsGoverned() {
        assertThat(COLLECTED_KINDS)
                .as("a kind the catalog omits carries no privacy class, no retention and no use decision — "
                        + "authorization is looked up by source kind, so it is evidence no governance covers")
                .isSubsetOf(CATALOG_KINDS);
    }

    @Test
    @DisplayName("every catalog source kind has a collector")
    void everyCatalogKindIsCollectable() {
        assertThat(CATALOG_KINDS)
                .as("a catalog kind nothing collects can only ever be reported absent: a practice requiring "
                        + "it abstains on every run, which reads as a broken product rather than a missing collector")
                .isSubsetOf(COLLECTED_KINDS);
    }
}
