package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.activity.overview.dto.PublicActivityDTO;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.settings.spi.PublicActivityPolicy;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceQueryService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.net.URI;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@WorkspaceAgnostic("Publication resolves an active, opted-in workspace and scopes every activity query to it")
class PublicActivityService {
    private final WorkspaceRepository workspaces;
    private final PublicActivityPolicy policy;
    private final ActivityPeopleService people;
    private final WorkspaceQueryService workspaceQueries;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public PublicActivityDTO page(String slug, ActivityPeopleRangeParams range, Set<String> repositoryKeys) {
        var workspace = workspaces.findPublicActivityWorkspace(slug);
        if (!policy.allowed() || workspace.isEmpty()) {
            var exception =
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "This public activity page is not available.");
            exception.getBody().setInstance(URI.create("/public/activity"));
            throw exception;
        }
        var selected = workspace.get();
        return PublicActivityDTO.from(
                selected.getDisplayName(),
                workspaceQueries.scmProviderType(selected),
                selected.isPublicActivitySearchEngines(),
                people.publicPeople(selected.getId(), range, repositoryKeys));
    }
}
