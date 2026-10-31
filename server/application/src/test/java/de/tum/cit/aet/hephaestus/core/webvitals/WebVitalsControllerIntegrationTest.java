package de.tum.cit.aet.hephaestus.core.webvitals;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@Tag("integration")
class WebVitalsControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private MeterRegistry registry;

    private static Map<String, Object> vital(
            String vital, String navigation, String page, String region, double value) {
        return Map.of("vital", vital, "navigation", navigation, "page", page, "region", region, "value", value);
    }

    private WebTestClient.ResponseSpec report(List<Map<String, Object>> vitals) {
        return webTestClient
                .post()
                .uri("/web-vitals")
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("vitals", vitals))
                .exchange();
    }

    @Test
    @WithUser
    void shouldRecordThePageLoadAndEachRouteViewApart() {
        double loads = timerCount("inp", "load", "ask_hephaestus", "header");
        double views = timerCount("inp", "route", "ask_hephaestus", "header");
        double shiftsBefore = shiftCount("route", "ask_hephaestus", "page");

        report(List.of(
                        vital("INP", "LOAD", "ASK_HEPHAESTUS", "HEADER", 180),
                        vital("INP", "ROUTE", "ASK_HEPHAESTUS", "HEADER", 90),
                        vital("INP", "ROUTE", "ASK_HEPHAESTUS", "HEADER", 120),
                        vital("CLS", "ROUTE", "ASK_HEPHAESTUS", "PAGE", 0.02)))
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        assertThat(timerCount("inp", "load", "ask_hephaestus", "header")).isEqualTo(loads + 1);
        assertThat(timerCount("inp", "route", "ask_hephaestus", "header")).isEqualTo(views + 2);
        assertThat(shiftCount("route", "ask_hephaestus", "page")).isEqualTo(shiftsBefore + 1);
    }

    @Test
    @WithUser
    void shouldRefuseAMeasurementWithoutAValue() {
        double before = timerCount("lcp", "load", "feedback", "page");
        var withoutValue =
                Map.<String, Object>of("vital", "LCP", "navigation", "LOAD", "page", "FEEDBACK", "region", "PAGE");
        var withNullValue = new HashMap<String, Object>(withoutValue);
        withNullValue.put("value", null);

        for (Map<String, Object> measurement : List.of(withoutValue, withNullValue)) {
            report(List.of(measurement)).expectStatus().isBadRequest().expectBody(Void.class);
        }

        assertThat(timerCount("lcp", "load", "feedback", "page")).isEqualTo(before);
    }

    @Test
    @WithUser
    void shouldRefuseAPageOrNavigationOutsideTheKnownSet() {
        report(List.of(vital("LCP", "LOAD", "/w/acme/secret-page", "PAGE", 900)))
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
        report(List.of(vital("LCP", "/w/acme/secret-page", "OTHER", "PAGE", 900)))
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
    }

    @Test
    @WithUser
    void shouldRefuseTheFirstByteOfARouteViewAndAReportOfMoreThanFortyMeasurements() {
        double views = timerCount("ttfb", "route", "other", "other");
        double loads = timerCount("ttfb", "load", "other", "other");

        report(List.of(vital("TTFB", "LOAD", "OTHER", "OTHER", 120), vital("TTFB", "ROUTE", "OTHER", "OTHER", 0)))
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
        report(Collections.nCopies(41, vital("TTFB", "LOAD", "OTHER", "OTHER", 120)))
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);

        assertThat(timerCount("ttfb", "route", "other", "other")).isEqualTo(views);
        assertThat(timerCount("ttfb", "load", "other", "other")).isEqualTo(loads);
    }

    @Test
    @WithUser
    void shouldRefuseAValueOutOfRange() {
        double before = timerCount("lcp", "load", "activity", "page");
        double shiftsBefore = shiftCount("load", "activity", "page");

        for (double value : new double[] {-1, 600_001}) {
            report(List.of(vital("LCP", "LOAD", "ACTIVITY", "PAGE", value)))
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(Void.class);
        }
        report(List.of(vital("CLS", "LOAD", "ACTIVITY", "PAGE", 101)))
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);

        assertThat(timerCount("lcp", "load", "activity", "page")).isEqualTo(before);
        assertThat(shiftCount("load", "activity", "page")).isEqualTo(shiftsBefore);
    }

    @Test
    void shouldRefuseAReaderWhoIsNotSignedIn() {
        String csrf = TestAuthUtils.fetchCsrfToken(webTestClient);
        webTestClient
                .post()
                .uri("/web-vitals")
                .headers(TestAuthUtils.withCsrf(csrf))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("vitals", List.of(vital("LCP", "LOAD", "OTHER", "PAGE", 900))))
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
    }

    private double shiftCount(String navigation, String page, String region) {
        var shift = registry.find("web.vital.layout_shift")
                .tags("navigation", navigation, "page", page, "region", region)
                .summary();
        return shift == null ? 0 : shift.count();
    }

    private double timerCount(String vital, String navigation, String page, String region) {
        var timer = registry.find("web.vital.duration")
                .tags("vital", vital, "navigation", navigation, "page", page, "region", region)
                .timer();
        return timer == null ? 0 : timer.count();
    }
}
