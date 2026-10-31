package de.tum.cit.aet.hephaestus.integration.slack.connect;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;

@Schema(description = "The Slack scopes that Hephaestus requests and the workspace's Slack token does not hold")
public record SlackScopesDTO(
        @NonNull @Schema(description = "Missing bot scopes, sorted; empty when the token holds every scope")
        List<String> missingScopes) {}
