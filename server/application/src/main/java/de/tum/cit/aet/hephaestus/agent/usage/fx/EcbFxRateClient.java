package de.tum.cit.aet.hephaestus.agent.usage.fx;

import de.tum.cit.aet.hephaestus.agent.LlmProperties;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Reads the European Central Bank's daily euro foreign-exchange reference rates. Rates are quoted as
 * foreign units per ONE euro, so the USD entry reads "how many USD one EUR buys"; that direction is
 * stored verbatim and inverted once, on read, in {@link FxRateInfoDTO#fromEcbRate}.
 *
 * <p>Never throws: every failure comes back as {@link Optional#empty()}, so a display-only nicety
 * cannot fail a scheduled tick and the last stored rate simply stays in place.
 */
@Component
@WorkspaceAgnostic("Public reference rates are instance-wide reference data, not tenant data")
public class EcbFxRateClient {

    private static final Logger log = LoggerFactory.getLogger(EcbFxRateClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String CUBE = "Cube";

    private final RestClient restClient;
    private final String dailyUrl;

    public EcbFxRateClient(LlmProperties llmProperties, RestClient.Builder restClients) {
        this.dailyUrl = llmProperties.fx().dailyUrl();
        // Its own short-timeout client: a slow ECB must never hold a scheduler thread.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT);
        factory.setReadTimeout(TIMEOUT);
        this.restClient = restClients.requestFactory(factory).build();
    }

    public Optional<EcbDailyRate> fetchLatestUsdRate() {
        String body;
        try {
            body = restClient.get().uri(dailyUrl).retrieve().body(String.class);
        } catch (Exception e) {
            log.warn(
                    "fx: ECB rate fetch failed, keeping the last stored rate: url={} reason={}",
                    dailyUrl,
                    e.toString());
            return Optional.empty();
        }
        if (body == null || body.isBlank()) {
            log.warn("fx: ECB returned an empty body: url={}", dailyUrl);
            return Optional.empty();
        }
        return parseUsdRate(body);
    }

    /**
     * Namespace-agnostic on purpose: the ECB puts {@code Cube} in a default namespace, so matching by
     * local name survives the ECB renaming or dropping the prefix.
     */
    static Optional<EcbDailyRate> parseUsdRate(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            var document = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            NodeList cubes = document.getElementsByTagNameNS("*", CUBE);
            for (int i = 0; i < cubes.getLength(); i++) {
                Element dated = (Element) cubes.item(i);
                String time = dated.getAttribute("time");
                if (time.isBlank()) {
                    continue;
                }
                Optional<BigDecimal> usd = findUsdRate(dated);
                if (usd.isPresent()) {
                    return Optional.of(new EcbDailyRate(LocalDate.parse(time), usd.get()));
                }
                log.warn("fx: ECB document has no USD entry for {}", time);
            }
            log.warn("fx: ECB document contained no dated Cube element");
            return Optional.empty();
        } catch (Exception e) {
            log.warn("fx: could not parse the ECB rate document: reason={}", e.toString());
            return Optional.empty();
        }
    }

    private static Optional<BigDecimal> findUsdRate(Element datedCube) {
        NodeList children = datedCube.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE || !CUBE.equals(child.getLocalName())) {
                continue;
            }
            Element currencyCube = (Element) child;
            if (!"USD".equals(currencyCube.getAttribute("currency"))) {
                continue;
            }
            String rate = currencyCube.getAttribute("rate");
            if (rate.isBlank()) {
                return Optional.empty();
            }
            BigDecimal parsed = new BigDecimal(rate);
            // A zero or negative quote would make the read-time inversion divide by zero or flip sign.
            return parsed.signum() > 0 ? Optional.of(parsed) : Optional.empty();
        }
        return Optional.empty();
    }

    /** The ECB's own direction: US dollars per 1 EUR. */
    public record EcbDailyRate(LocalDate date, BigDecimal usdPerEur) {}
}
