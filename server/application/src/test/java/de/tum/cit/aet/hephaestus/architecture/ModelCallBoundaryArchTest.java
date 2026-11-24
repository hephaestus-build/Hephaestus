package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaMethodReference;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.proxy.LlmModelProbeGateway;
import java.util.function.Function;
import okhttp3.OkHttpClient;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.netty.http.client.HttpClient;

/**
 * The server sends a model request only through the model proxy, which fences, bills and bounds it. Every agent loop
 * runs on Pi in a sandbox. The capability probe is the only server-side caller of the proxy's sender. The proxy's upstream sender is package-private, so the compiler keeps other code from it. A class
 * outside the proxy that read the provider key of a resolved model could call the model past every one of those checks.
 * Connection setup still reads a stored key for its metadata lookups (`LlmConnectionProbeService`), which call no model.
 */
class ModelCallBoundaryArchTest extends HephaestusArchitectureTest {
    private static final String PROXY = "de.tum.cit.aet.hephaestus.agent.proxy..";

    private static final String PROXY_SERVICE = "de.tum.cit.aet.hephaestus.agent.proxy.LlmProxyService";

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

    /** No other JVM caller can make a model call on its own routing, including report drafting. */
    static final ArchRule ONLY_PROBES_ENTER_WITHOUT_A_RUN = noClasses()
            .that()
            .doNotHaveFullyQualifiedName(LlmModelProbeGateway.class.getName())
            .and()
            .doNotHaveFullyQualifiedName(PROXY_SERVICE)
            .should()
            .accessTargetWhere(DescribedPredicate.<JavaAccess<?>>describe(
                    "the proxy's in-process model sender",
                    access -> access instanceof JavaCodeUnitAccess<?>
                            && access.getTargetOwner().getName().equals(PROXY_SERVICE)
                            && access.getName().equals("serve")))
            .because("a draft calls its model through the sandbox run credential");

    /** The model egress guard customizes the Spring client builders; an OkHttp client built here would be outside it. */
    static final ArchRule NO_OWN_OKHTTP_CLIENT = noClasses()
            .should()
            .callConstructor(OkHttpClient.class)
            .orShould()
            .callConstructor(OkHttpClient.Builder.class)
            .because("the model egress guard sees only clients built from the application's Spring builders");

    /**
     * Reactor Netty's client sends a request with no Spring builder, so the egress guard never sees it. Server code may
     * configure one as a connector for a WebClient, but not send with it.
     */
    static final ArchRule NO_DIRECT_REACTOR_NETTY_REQUEST = noClasses()
            .should()
            .accessTargetWhere(DescribedPredicate.<JavaAccess<?>>describe(
                    "a request sent with Reactor Netty's client", access -> sendsWithReactorNetty(access)))
            .because("the model egress guard sees only requests sent through the application's Spring clients");

    /**
     * A call whose target returns Reactor Netty's send API, or any method reference to Reactor Netty's client: a
     * reference carries no return type to read, and a connector is configured by call, never by reference.
     */
    private static boolean sendsWithReactorNetty(JavaAccess<?> access) {
        if (access instanceof JavaMethodReference)
            return access.getTargetOwner().isAssignableTo(HttpClient.class);
        return access.getTarget() instanceof AccessTarget.CodeUnitAccessTarget unit
                && (unit.getRawReturnType().isAssignableTo(HttpClient.ResponseReceiver.class)
                        || unit.getRawReturnType().isAssignableTo(HttpClient.WebsocketReceiver.class));
    }

    @Test
    @DisplayName("only the model proxy reads a model's provider key")
    void onlyTheProxyCallsAModel() {
        ONLY_THE_PROXY_READS_A_MODEL_KEY.check(classes);
    }

    @Test
    @DisplayName("only capability probes can enter the model proxy without a run")
    void onlyProbesEnterWithoutARun() {
        ONLY_PROBES_ENTER_WITHOUT_A_RUN.check(classes);
    }

    @Test
    @DisplayName("server code builds no OkHttp client of its own")
    void noServerCodeBuildsAnOkHttpClient() {
        NO_OWN_OKHTTP_CLIENT.check(classes);
    }

    @Test
    @DisplayName("server code sends no request with Reactor Netty's client directly")
    void noServerCodeSendsWithReactorNettyDirectly() {
        NO_DIRECT_REACTOR_NETTY_REQUEST.check(classes);
    }

    /** Classes that send a model request with a client the egress guard never sees. */
    static final class OwnOkHttpClient {
        OkHttpClient client() {
            return new OkHttpClient.Builder().build();
        }
    }

    static final class OwnReactorNettyRequest {
        Object ask() {
            return HttpClient.create().post().uri("http://model.invalid/v1/chat/completions");
        }
    }

    static final class OwnReactorNettyRequestByReference {
        Function<HttpClient, ?> ask() {
            return HttpClient::post;
        }
    }

    @Test
    @DisplayName("a client outside the egress guard fails its rule")
    void aClientOutsideTheEgressGuardFails() {
        var importer = new ClassFileImporter();

        assertThat(NO_OWN_OKHTTP_CLIENT
                        .evaluate(importer.importClasses(OwnOkHttpClient.class))
                        .hasViolation())
                .isTrue();
        assertThat(NO_DIRECT_REACTOR_NETTY_REQUEST
                        .evaluate(importer.importClasses(OwnReactorNettyRequest.class))
                        .hasViolation())
                .isTrue();
        assertThat(NO_DIRECT_REACTOR_NETTY_REQUEST
                        .evaluate(importer.importClasses(OwnReactorNettyRequestByReference.class))
                        .hasViolation())
                .isTrue();
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
