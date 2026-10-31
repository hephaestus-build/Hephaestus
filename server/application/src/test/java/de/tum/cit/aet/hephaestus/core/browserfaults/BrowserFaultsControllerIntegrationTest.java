package de.tum.cit.aet.hephaestus.core.browserfaults;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@Tag("integration")
class BrowserFaultsControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private MeterRegistry registry;

    private static Map<String, Object> fault(String errorType, String release, String chunk) {
        return Map.of(
                "kind",
                "UNCAUGHT_ERROR",
                "page",
                "OTHER",
                "errorType",
                errorType,
                "release",
                release,
                "frames",
                List.of(Map.of("chunk", chunk, "line", 1, "column", 2345)),
                "count",
                2);
    }

    private WebTestClient.ResponseSpec anonymously(Object body, String fetchSite) {
        return anonymously(body, headers -> headers.set("Sec-Fetch-Site", fetchSite));
    }

    private WebTestClient.ResponseSpec anonymously(Object body, Consumer<HttpHeaders> browser) {
        String csrf = TestAuthUtils.fetchCsrfToken(webTestClient);
        return webTestClient
                .post()
                .uri("/browser-faults")
                .headers(TestAuthUtils.withCsrf(csrf))
                .headers(browser)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    @Test
    void shouldRecordAFaultFromAReaderWhoIsNotSignedIn() {
        double before = faults("syntax_error");

        anonymously(Map.of("faults", List.of(fault("SYNTAX_ERROR", "0.95.1", "CRYwA6-M"))), "same-origin")
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        assertThat(faults("syntax_error")).isEqualTo(before + 2);
    }

    @Test
    @WithUser
    void shouldRecordAFaultFromASignedInReaderThatTheBrowserMarksSameSite() {
        double before = faults("range_error");

        webTestClient
                .post()
                .uri("/browser-faults")
                .headers(TestAuthUtils.withCurrentUser())
                .header("Sec-Fetch-Site", "same-site")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("faults", List.of(fault("RANGE_ERROR", "a26a81cf04", "DqCAn3iK"))))
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        assertThat(faults("range_error")).isEqualTo(before + 2);
    }

    @Test
    void shouldRefuseAReportWithTextThatTheCallerChose() {
        double before = faults("other");

        for (Map<String, Object> chosen : List.of(
                fault("OTHER", "0.95.1", "../reset-token"),
                fault("OTHER", "reset-token-of-ada", "CRYwA6-M"),
                fault("Cannot read properties of undefined", "0.95.1", "CRYwA6-M"))) {
            anonymously(Map.of("faults", List.of(chosen)), "same-origin")
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(Void.class);
        }

        assertThat(faults("other")).isEqualTo(before);
    }

    @Test
    void shouldRefuseAReportThatAnotherSiteSentOrThatIsTooLarge() {
        double before = faults("type_error");
        var report = Map.of("faults", List.of(fault("TYPE_ERROR", "0.95.1", "CRYwA6-M")));

        anonymously(report, "cross-site").expectStatus().isForbidden().expectBody(Void.class);
        var padded = new HashMap<String, Object>(report);
        padded.put("padding", "x".repeat(17 * 1024));
        double oversized = refused("payload-too-large");
        anonymously(padded, "same-origin").expectStatus().isEqualTo(413).expectBody(Void.class);

        assertThat(faults("type_error")).isEqualTo(before);
        assertThat(refused("payload-too-large")).isEqualTo(oversized + 1);
    }

    @Test
    void shouldAcceptTheWebAppsOriginWithoutFetchMetadataAndRefuseAnotherOriginOrAMissingToken() {
        double before = faults("type_error");
        var report = Map.of("faults", List.of(fault("TYPE_ERROR", "0.95.1", "CRYwA6-M")));

        // An older browser sends no Fetch Metadata.
        anonymously(report, headers -> headers.setOrigin("http://localhost:4200"))
                .expectStatus()
                .isNoContent();
        anonymously(report, headers -> headers.setOrigin("https://hephaestus.evil.example"))
                .expectStatus()
                .isForbidden();
        webTestClient
                .post()
                .uri("/browser-faults")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(report)
                .exchange()
                .expectStatus()
                .isForbidden();

        assertThat(faults("type_error")).isEqualTo(before + 2);
    }

    @Test
    void shouldDropAFieldThatItDoesNotKnowWithoutLoggingIt() {
        Logger logger = (Logger) LoggerFactory.getLogger(BrowserFaultRecorder.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var frame = Map.of("chunk", "CRYwA6-M", "line", 1, "column", 2, "url", "https://x/private-frame");
            var fault = new HashMap<String, Object>(fault("OTHER", "0.95.1", "CRYwA6-M"));
            fault.put("message", "private-fault-text");
            fault.put("frames", List.of(frame));
            anonymously(Map.of("faults", List.of(fault), "message", "private-report-text"), "same-origin")
                    .expectStatus()
                    .isNoContent();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.getFirst().getKeyValuePairs())
                .allSatisfy(pair -> assertThat(String.valueOf(pair.value)).doesNotContain("private"));
    }

    private double refused(String reason) {
        var counter =
                registry.find("browser.faults.refused").tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    private double faults(String errorType) {
        var counter = registry.find("browser.faults")
                .tags("kind", "uncaught_error", "page", "other", "error_type", errorType)
                .counter();
        return counter == null ? 0 : counter.count();
    }
}
