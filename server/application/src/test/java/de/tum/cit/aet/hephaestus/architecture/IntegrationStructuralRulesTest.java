package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.GENERATED_GRAPHQL_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Structural fitness functions pinning the integration package layout and vendor-neutral core. */
class IntegrationStructuralRulesTest extends HephaestusArchitectureTest {

    private static final Set<String> VENDOR_LITERALS = Set.of("GitHub", "GitLab", "Slack", "Outline");

    @Test
    void scmDomainDoesNotDependOnVendorAdapters() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..integration.scm.domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..integration.scm.github..", "..integration.scm.gitlab..")
                .because("SCM shared kernel must remain vendor-neutral.");
        rule.check(classes);
    }

    @Test
    void spiHasNoVendorLiteralIdentifiers() {
        ArchRule rule = classes()
                .that()
                .resideInAPackage("..integration.core.spi..")
                .should(hasNoVendorLiteralIn("simple class name", JavaClass::getSimpleName))
                .because("Unified SPI must be vendor-neutral by name.");
        rule.check(classes);
    }

    @Test
    void coreConsumerHasNoVendorLiteralIdentifiers() {
        ArchRule classRule = classes()
                .that()
                .resideInAPackage("..integration.core.consumer..")
                .should(hasNoVendorLiteralIn("simple class name", JavaClass::getSimpleName))
                .because("Consumer wiring must be vendor-neutral.");
        ArchRule fieldRule = fields().that()
                .areDeclaredInClassesThat()
                .resideInAPackage("..integration.core.consumer..")
                .should(hasNoVendorLiteralIn("field name", JavaField::getName))
                .because("Vendor-literal field names re-pin the consumer to a specific provider.");
        ArchRule methodRule = methods()
                .that()
                .areDeclaredInClassesThat()
                .resideInAPackage("..integration.core.consumer..")
                .should(hasNoVendorLiteralIn("method name", JavaMethod::getName))
                .because("Vendor-literal method names re-pin the consumer to a specific provider.");
        classRule.check(classes);
        fieldRule.check(classes);
        methodRule.check(classes);
    }

    @Test
    void classNamesSpellGitHubAndGitLabAsTheBrandsDo() {
        // The generated GitLab client keeps the schema's own spelling (GLGitlabSubscription).
        ArchRule rule = noClasses()
                .that()
                .resideOutsideOfPackage(GENERATED_GRAPHQL_PACKAGE)
                .should()
                .haveSimpleNameContaining("Github")
                .orShould()
                .haveSimpleNameContaining("Gitlab")
                .because("the brands are spelled GitHub and GitLab; a second spelling splits every search and "
                        + "rename in two.");
        rule.check(classes);
    }

    @Test
    void integrationTopLevelHasOnlyExpectedSubpackages() throws IOException {
        Path integrationDir = locateIntegrationRoot();
        try (Stream<Path> children = Files.list(integrationDir)) {
            Set<String> actual = children.map(p -> p.getFileName().toString()).collect(Collectors.toSet());
            // {identity} owns the OIDC login adapters (ADR 0017). It lives here rather than in core.auth
            // because core.auth must never import integration.*.
            Set<String> expected = Set.of("core", "scm", "slack", "identity", "outline", "package-info.java");
            assertThat(actual).isEqualTo(expected);
        }
    }

    private static <T> ArchCondition<T> hasNoVendorLiteralIn(String what, Function<? super T, String> identifier) {
        return new ArchCondition<T>("have no vendor literal (GitHub/GitLab/Slack/Outline) in the " + what) {
            @Override
            public void check(T item, ConditionEvents events) {
                String name = identifier.apply(item);
                String literal = findVendorLiteral(name);
                if (literal != null) {
                    events.add(SimpleConditionEvent.violated(
                            item, what + " '" + name + "' contains vendor literal '" + literal + "'"));
                }
            }
        };
    }

    private static @Nullable String findVendorLiteral(String identifier) {
        String lowered = identifier.toLowerCase(Locale.ROOT);
        for (String literal : VENDOR_LITERALS) {
            if (lowered.contains(literal.toLowerCase(Locale.ROOT))) {
                return literal;
            }
        }
        return null;
    }

    private static Path locateIntegrationRoot() {
        Path serverDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path integrationDir = serverDir.resolve("src/main/java/de/tum/cit/aet/hephaestus/integration");
        if (!Files.isDirectory(integrationDir)) {
            integrationDir =
                    serverDir.resolve("server/application/src/main/java/de/tum/cit/aet/hephaestus/integration");
        }
        if (!Files.isDirectory(integrationDir)) {
            throw new IllegalStateException("Could not locate integration/ source root from user.dir=" + serverDir);
        }
        return integrationDir;
    }
}
