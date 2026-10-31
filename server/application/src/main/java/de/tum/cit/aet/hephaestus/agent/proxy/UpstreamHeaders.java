package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmAuthMode;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** The headers a provider call carries: the content type and the credential in the shape the provider's protocol expects. */
public final class UpstreamHeaders {
    private UpstreamHeaders() {}

    public static HttpHeaders of(HttpHeaders incomingHeaders, LlmModelResolver.ProxyCredential credential) {
        HttpHeaders outgoing = new HttpHeaders();
        outgoing.setContentType(MediaType.APPLICATION_JSON);
        if (incomingHeaders.getFirst(HttpHeaders.ACCEPT) != null) {
            outgoing.set(HttpHeaders.ACCEPT, incomingHeaders.getFirst(HttpHeaders.ACCEPT));
        }
        outgoing.set(HttpHeaders.ACCEPT_ENCODING, "identity");

        if (LlmApiProtocol.ANTHROPIC_MESSAGES.wire().equals(credential.apiProtocol())) {
            outgoing.set("anthropic-version", "2023-06-01");
            if (credential.apiKey() != null && !credential.apiKey().isBlank())
                outgoing.set("x-api-key", credential.apiKey());
            return outgoing;
        }
        if (credential.apiKey() != null && !credential.apiKey().isBlank()) {
            if (credential.authMode() == LlmAuthMode.API_KEY) {
                outgoing.set("api-key", credential.apiKey());
            } else {
                outgoing.setBearerAuth(credential.apiKey());
            }
        }
        return outgoing;
    }
}
