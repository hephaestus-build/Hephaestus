package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.activity.overview.dto.PublicActivityDTO;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.settings.spi.PublicActivityPolicy;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.net.URI;
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

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public Publication page(String slug, ActivityPeopleRangeParams range) {
        var workspace = workspaces.findPublicActivityWorkspace(slug);
        if (!policy.allowed() || workspace.isEmpty()) {
            var exception =
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "This public activity page is not available.");
            exception.getBody().setInstance(URI.create("/public/activity"));
            throw exception;
        }
        var selected = workspace.get();
        return new Publication(
                PublicActivityDTO.from(selected.getDisplayName(), people.publicPeople(selected.getId(), range)),
                selected.isPublicActivitySearchEngines());
    }

    record Publication(PublicActivityDTO activity, boolean allowSearchEngines) {}
}
