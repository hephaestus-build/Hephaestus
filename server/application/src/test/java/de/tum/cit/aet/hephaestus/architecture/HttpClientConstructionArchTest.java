package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaMethodCall;
import java.net.HttpURLConnection;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The application's own HTTP clients start from the {@code WebClient.Builder}, {@code RestClient.Builder} or
 * {@code RestTemplateBuilder} that Spring injects, so its codecs, observation and customizers apply. A client may
 * still choose its own connector, request factory and codecs on that builder. Vendor SDKs with their own transport,
 * such as Slack's, are outside this rule.
 */
class HttpClientConstructionArchTest extends HephaestusArchitectureTest {

    private static final Set<String> STATIC_CONSTRUCTORS = Set.of("builder", "create");

    @Test
    void shouldBuildEveryHttpClientFromAnInjectedSpringBuilder() {
        noClasses()
                .should()
                .callMethodWhere(describe(
                        "WebClient or RestClient built without the injected builder",
                        (JavaMethodCall call) -> STATIC_CONSTRUCTORS.contains(call.getName())
                                && (call.getTargetOwner().isEquivalentTo(WebClient.class)
                                        || call.getTargetOwner().isEquivalentTo(RestClient.class))))
                .because("a client built from the static builder misses the application's codecs and observation")
                .check(classes);
    }

    @Test
    void shouldBuildEveryRestTemplateFromTheInjectedBuilder() {
        noClasses()
                .should()
                .callConstructor(RestTemplate.class)
                .because("a RestTemplate created directly misses the application's converters and observation")
                .check(classes);
    }

    @Test
    void shouldReachHttpOnlyThroughSpringClients() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAPackage("java.net.http..")
                .orShould()
                .dependOnClassesThat()
                .areAssignableTo(HttpURLConnection.class)
                .because("java.net requests bypass the application's codecs, observation and customizers")
                .check(classes);
    }
}
