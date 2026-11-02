package de.tum.cit.aet.hephaestus.integration.scm.gitlab.common;

import org.springframework.web.reactive.function.client.WebClient;

/**
 * The builder of every GitLab API client. The server URL comes from configuration and DNS, and each call carries a
 * token, so each client checks the address that it connects to, not only the URL that it was given.
 */
@FunctionalInterface
public interface GitLabWebClients {
    WebClient.Builder builder();
}
