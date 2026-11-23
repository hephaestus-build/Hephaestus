package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import java.util.function.Function;
import okhttp3.OkHttpClient;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The server sends a model request only through the model proxy, which fences, bills and bounds it. Every agent loop
 * runs on Pi in a sandbox; the server's own calls, such as a report draft or a capability probe, enter the proxy through
 * its gateways. The proxy's upstream sender is package-private, so the compiler keeps other code from it. A class
 * outside the proxy that read the provider key of a resolved model could call the model past every one of those checks.
 * Connection setup still reads a stored key for its metadata lookups (`LlmConnectionProbeService`), which call no model.
 */
class ModelCallBoundaryArchTest extends HephaestusArchitectureTest {
    private static final String PROXY = "de.tum.cit.aet.hephaestus.agent.proxy..";

    /** A call and a method reference both count: either hands the key to the caller. */
    static final ArchRule ONLY_THE_PROXY_READS_A_MODEL_KEY = noClasses()
            .that()
            .resideOutsideOfPackage(PROXY)
            .should()
            .accessTargetWhere(DescribedPredicate.<JavaAccess<?>>describe(
                    "the provider key of a resolved model",
                    access -> access instanceof JavaCodeUnitAccess<?>
                            && access.getTargetOwner().isEquivalentTo(LlmModelResolver.ProxyCredential.class)
                            && access.getName().equals("apiKey")))
            .because("only the model proxy authenticates a model call");

    /** The model egress guard customizes the Spring client builders; an OkHttp client built here would be outside it. */
    static final ArchRule NO_OWN_OKHTTP_CLIENT = noClasses()
            .should()
            .callConstructor(OkHttpClient.class)
            .orShould()
            .callConstructor(OkHttpClient.Builder.class)
            .because("the model egress guard sees only clients built from the application's Spring builders");

    @Test
    @DisplayName("only the model proxy reads a model's provider key")
    void onlyTheProxyCallsAModel() {
        ONLY_THE_PROXY_READS_A_MODEL_KEY.check(classes);
    }

    @Test
    @DisplayName("server code builds no OkHttp client of its own")
    void noServerCodeBuildsAnOkHttpClient() {
        NO_OWN_OKHTTP_CLIENT.check(classes);
    }

    /** Classes that call a model with their own client, past the proxy. */
    static final class OwnModelClient {
        @Nullable
        String authorization(LlmModelResolver.InstanceModel model) {
            return model.credential().apiKey();
        }
    }

    static final class OwnModelClientByReference {
        Function<LlmModelResolver.ProxyCredential, @Nullable String> authorization() {
            return LlmModelResolver.ProxyCredential::apiKey;
        }
    }

    @Test
    @DisplayName("a class that calls a model outside the proxy fails the rule")
    void aModelCallOutsideTheProxyFails() {
        var importer = new ClassFileImporter();

        assertThat(ONLY_THE_PROXY_READS_A_MODEL_KEY
                        .evaluate(importer.importClasses(OwnModelClient.class))
                        .hasViolation())
                .isTrue();
        assertThat(ONLY_THE_PROXY_READS_A_MODEL_KEY
                        .evaluate(importer.importClasses(OwnModelClientByReference.class))
                        .hasViolation())
                .isTrue();
    }
}
