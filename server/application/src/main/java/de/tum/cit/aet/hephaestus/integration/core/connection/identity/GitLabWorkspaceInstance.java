package de.tum.cit.aet.hephaestus.integration.core.connection.identity;

import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.WorkspaceProviderAvailability;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * The GitLab instance a workspace can be created on: the operator's default one, which GitLab sync reads every
 * project, member and event from. A request naming another instance is refused before its token is sent or
 * stored.
 */
@Component
public class GitLabWorkspaceInstance {

    private final List<WorkspaceProviderAvailability> providers;

    public GitLabWorkspaceInstance(List<WorkspaceProviderAvailability> providers) {
        this.providers = providers;
    }

    /** The default instance as the operator configured it, when {@code serverUrl} is blank or names it. */
    public String require(@Nullable String serverUrl) {
        String instance = providers.stream()
                .filter(provider -> provider.kind() == IntegrationKind.GITLAB)
                .findFirst()
                .flatMap(WorkspaceProviderAvailability::hintUrl)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.FORBIDDEN, "GitLab workspace creation is not enabled"));
        if (serverUrl == null || serverUrl.isBlank()) {
            return instance;
        }
        Optional<String> requested = ScmOrigin.of(serverUrl);
        if (requested.isEmpty() || !requested.equals(ScmOrigin.of(instance))) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "Hephaestus reads GitLab only from " + instance + ", so a workspace cannot be created on "
                            + serverUrl.trim());
        }
        return instance;
    }
}
