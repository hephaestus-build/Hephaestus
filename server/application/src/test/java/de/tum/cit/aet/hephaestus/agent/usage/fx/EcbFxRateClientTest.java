package de.tum.cit.aet.hephaestus.agent.usage.fx;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.LlmProperties;
import de.tum.cit.aet.hephaestus.agent.usage.fx.EcbFxRateClient.EcbDailyRate;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Optional;
import java.util.stream.Stream;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.client.RestClient;

class EcbFxRateClientTest extends BaseUnitTest {

    private MockWebServer ecb;

    @BeforeEach
    void setUp() throws IOException {
        ecb = new MockWebServer();
        ecb.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        ecb.close();
    }

    private EcbFxRateClient clientFor(String path) {
        return clientForUrl(ecb.url(path).toString());
    }

    private static EcbFxRateClient clientForUrl(String dailyUrl) {
        return new EcbFxRateClient(
                new LlmProperties("", new LlmProperties.Egress(false), new LlmProperties.Fx(dailyUrl)),
                RestClient.builder());
    }

    private static String fixture() throws IOException {
        try (InputStream in = EcbFxRateClientTest.class.getResourceAsStream("/agent/fx/eurofxref-daily.xml")) {
            assertThat(in).as("ECB fixture must be on the test classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void enqueueXml(String body) {
        ecb.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/xml;charset=UTF-8")
                .body(body)
                .build());
    }

    @Test
    @DisplayName("should read the dated USD rate out of a real ECB document")
    void shouldParseUsdRateWhenGivenRealEcbDocument() throws IOException {
        Optional<EcbDailyRate> parsed = EcbFxRateClient.parseUsdRate(fixture());

        assertThat(parsed).hasValueSatisfying(rate -> {
            assertThat(rate.date()).isEqualTo(LocalDate.of(2026, 7, 24));
            // Stored in the ECB's own direction: US dollars per ONE euro, so above 1. The fixture
            // lists 30 other currencies (GBP at 0.86545 among them) — pinning the exact value is
            // already the proof that USD, and only USD, was picked out.
            assertThat(rate.usdPerEur()).isEqualByComparingTo("1.1377");
        });
    }

    static Stream<Arguments> unusableDocuments() {
        return Stream.of(
                Arguments.of("""
                    <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01"
                                     xmlns="http://www.ecb.int/vocabulary/2002-08-01/eurofxref">
                      <Cube><Cube time='2026-07-24'><Cube currency='GBP' rate='0.86545'/></Cube></Cube>
                    </gesmes:Envelope>
                    """, "a well-formed document that quotes every currency except USD"),
                Arguments.of("<html><body>503 Service Unavailable", "an error page served where XML was expected"),
                Arguments.of("""
                    <?xml version="1.0"?>
                    <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                    <Cube><Cube time='2026-07-24'><Cube currency='USD' rate='1.1377'/></Cube></Cube>
                    """, "a DOCTYPE, refused outright so no external entity can be expanded"),
                Arguments.of(
                        "<Cube><Cube time='2026-07-24'><Cube currency='USD' rate='0'/></Cube></Cube>",
                        "a zero quote, which the read-time inversion would divide by"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("unusableDocuments")
    void shouldReturnEmptyForAnUnusableDocument(String xml, String why) {
        assertThat(EcbFxRateClient.parseUsdRate(xml)).as(why).isEmpty();
    }

    @Test
    @DisplayName("should fetch and parse the daily document over HTTP")
    void shouldReturnRateWhenEcbAnswers() throws Exception {
        enqueueXml(fixture());

        Optional<EcbDailyRate> fetched =
                clientFor("/stats/eurofxref/eurofxref-daily.xml").fetchLatestUsdRate();

        assertThat(fetched)
                .hasValueSatisfying(rate -> assertThat(rate.usdPerEur()).isEqualByComparingTo("1.1377"));
        assertThat(ecb.takeRequest().getTarget()).isEqualTo("/stats/eurofxref/eurofxref-daily.xml");
    }

    /**
     * A bad status and an unreachable host reach the caller identically — both leave the single
     * {@code retrieve().body(...)} statement by exception, into the one catch that turns any transport
     * failure into an empty Optional. One of the two is enough to pin that catch.
     */
    @Test
    @DisplayName("should return empty without throwing when the ECB answers 500")
    void shouldReturnEmptyRatherThanThrowWhenEcbReturnsServerError() {
        ecb.enqueue(new MockResponse.Builder().code(500).body("upstream boom").build());

        assertThat(clientFor("/daily.xml").fetchLatestUsdRate()).isEmpty();
    }

    @Test
    @DisplayName("should return empty without throwing when the body is empty")
    void shouldReturnEmptyRatherThanThrowWhenTheBodyIsEmpty() {
        ecb.enqueue(new MockResponse.Builder().code(200).body("").build());

        assertThat(clientFor("/daily.xml").fetchLatestUsdRate()).isEmpty();
    }
}
