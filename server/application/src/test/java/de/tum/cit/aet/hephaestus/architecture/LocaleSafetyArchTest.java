package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Default-locale case folding corrupts ASCII on Turkish-locale JVMs (dotted-i bug). Error Prone's
 * {@code StringCaseLocaleUsage} rejects a case fold without a locale everywhere; inside the webhook
 * package the subject grammar must not depend on the JVM locale at all, so its second suggestion,
 * {@link Locale#getDefault()}, is banned there too.
 */
class LocaleSafetyArchTest extends HephaestusArchitectureTest {

    private static final String WEBHOOK_PACKAGE = "..integration.core.webhook..";

    @Test
    void noLocaleGetDefault() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage(WEBHOOK_PACKAGE)
                .should()
                .callMethod(Locale.class, "getDefault")
                .because("Locale.getDefault() threads JVM-default locale into case-folds — use Locale.ROOT");
        rule.check(classes);
    }
}
