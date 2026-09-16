package de.tum.cit.aet.hephaestus.core.security;

import de.tum.cit.aet.hephaestus.core.WebClientConnectors;
import java.net.URI;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** SCM endpoint validation shared by workspace input and outbound preflight requests. */
@Component
public class ScmServerEndpointPolicy {
    private final String simulationOrigin;

    public ScmServerEndpointPolicy(Environment environment) {
        simulationOrigin = environment.getProperty("hephaestus.e2e.scm-origin", "");
        if (!simulationOrigin.isEmpty()) {
            if (!environment.acceptsProfiles(Profiles.of("e2e & !prod"))) {
                throw new IllegalArgumentException("SCM simulation requires the e2e profile without prod");
            }
            URI uri = URI.create(simulationOrigin);
            if (!"http".equals(uri.getScheme())
                    || !"127.0.0.1".equals(uri.getHost())
                    || uri.getPort() < 1
                    || uri.getPort() > 65535
                    || uri.getRawUserInfo() != null
                    || !uri.getRawPath().isEmpty()
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                throw new IllegalArgumentException(
                        "SCM simulation origin must be http://127.0.0.1:<port> without a trailing slash");
            }
        }
    }

    public void validate(String serverUrl) {
        if (!isSimulation(serverUrl)) {
            ServerUrlValidator.validate(serverUrl);
        }
    }

    public WebClient clientFor(String serverUrl) {
        validate(serverUrl);
        URI origin = URI.create(serverUrl);
        return WebClient.builder()
                .clientConnector(WebClientConnectors.ssrfGuarded(isSimulation(serverUrl)))
                .filter((request, next) -> {
                    URI target = request.url();
                    if (!origin.getScheme().equals(target.getScheme())
                            || !origin.getRawAuthority().equals(target.getRawAuthority())) {
                        return Mono.error(
                                new IllegalArgumentException("SCM request must stay on its validated origin"));
                    }
                    return next.exchange(request);
                })
                .build();
    }

    private boolean isSimulation(String serverUrl) {
        return !simulationOrigin.isEmpty()
                && (simulationOrigin.equals(serverUrl) || (simulationOrigin + "/").equals(serverUrl));
    }
}
