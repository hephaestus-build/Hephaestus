package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The server sends a model request only through the model proxy, which fences, bills and bounds it. Every agent loop
 * runs on Pi in a sandbox; the server's own calls, such as a report draft or a capability probe, enter the proxy through
 * its gateways. The proxy's upstream sender is package-private, so the compiler keeps other code from it. A class
 * outside the proxy that read a model's provider key could call the model past every one of those checks.
 */
class ModelCallBoundaryArchTest extends HephaestusArchitectureTest {
    private static final String PROXY = "de.tum.cit.aet.hephaestus.agent.proxy..";

    static final ArchRule ONLY_THE_PROXY_READS_A_MODEL_KEY = noClasses()
            .that()
            .resideOutsideOfPackage(PROXY)
            .should()
            .callMethod(LlmModelResolver.ProxyCredential.class, "apiKey")
            .because("only the model proxy authenticates a model call");

    @Test
    @DisplayName("only the model proxy reads a model's provider key")
    void onlyTheProxyCallsAModel() {
        ONLY_THE_PROXY_READS_A_MODEL_KEY.check(classes);
    }

    /** A class that calls a model with its own client, past the proxy. */
    static final class OwnModelClient {
        @Nullable
        String authorization(LlmModelResolver.InstanceModel model) {
            return model.credential().apiKey();
        }
    }

    @Test
    @DisplayName("a class that calls a model outside the proxy fails the rule")
    void aModelCallOutsideTheProxyFails() {
        var fixture = new ClassFileImporter().importClasses(OwnModelClient.class);

        assertThat(ONLY_THE_PROXY_READS_A_MODEL_KEY.evaluate(fixture).hasViolation())
                .isTrue();
    }
}
