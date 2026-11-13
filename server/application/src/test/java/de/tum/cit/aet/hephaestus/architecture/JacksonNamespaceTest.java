package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import de.tum.cit.aet.hephaestus.config.JSpecifyRequiredModelConverter;
import org.junit.jupiter.api.Test;

/**
 * Spring Boot 4 wires Jackson 3 ({@code tools.jackson.*}). Jackson 2 jars stay on the classpath as transitive
 * dependencies, so import autocompletion reintroduces a second JSON stack with other defaults unless a rule refuses
 * it.
 *
 * <p>Annotation imports ({@code com.fasterxml.jackson.annotation.*}) stay valid: Jackson 3 reads that shared
 * namespace natively. {@link JSpecifyRequiredModelConverter} implements swagger-core's {@code ModelConverter}, whose
 * API exposes a Jackson 2 {@code JavaType}, and its test reads swagger-core's Jackson 2 output.
 */
class JacksonNamespaceTest extends HephaestusArchitectureTest {

    @Test
    void shouldUseJackson3WhenCodeReadsOrWritesJson() {
        ArchRule rule = noClasses()
                .that()
                .doNotBelongToAnyOf(JSpecifyRequiredModelConverter.class)
                .and()
                .doNotHaveFullyQualifiedName(JSpecifyRequiredModelConverter.class.getName() + "Test")
                .should()
                .dependOnClassesThat(resideInAPackage("com.fasterxml.jackson..")
                        .and(not(resideInAPackage("com.fasterxml.jackson.annotation.."))))
                .because("Spring Boot 4 wires Jackson 3 (tools.jackson.*); see"
                        + " https://github.com/FasterXML/jackson/blob/main/jackson3/MIGRATING_TO_JACKSON_3.md");
        rule.check(classesWithTests);
    }
}
