package de.tum.cit.aet.hephaestus.core.auth.mcp;

import de.tum.cit.aet.hephaestus.core.WebClientConnectors;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Fetches a document over HTTPS through the connect-time SSRF guard, which also stops DNS rebinding. The
 * connector follows no redirect, and a body over the CIMD limit fails the read.
 */
@Component
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
class HttpClientMetadataSource implements ClientMetadataSource {

    private static final Logger log = LoggerFactory.getLogger(HttpClientMetadataSource.class);

    /** CIMD § 6.6 recommends at most 5 KB. */
    static final int MAX_DOCUMENT_BYTES = 5 * 1024;

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final WebClient webClient;
    private final Duration timeout;

    @Autowired
    HttpClientMetadataSource(WebClient.Builder webClients) {
        this(webClients, WebClientConnectors.ssrfGuarded(), TIMEOUT);
    }

    HttpClientMetadataSource(WebClient.Builder webClients, ClientHttpConnector connector, Duration timeout) {
        this.webClient = webClients
                .clientConnector(connector)
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_DOCUMENT_BYTES))
                .build();
        this.timeout = timeout;
    }

    @Override
    public Optional<String> fetch(URI clientId) {
        try {
            return Optional.ofNullable(webClient
                    .get()
                    .uri(clientId)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(timeout));
        } catch (RuntimeException unreadable) {
            log.info(
                    "mcp.client: client metadata document could not be read: {}",
                    unreadable.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
