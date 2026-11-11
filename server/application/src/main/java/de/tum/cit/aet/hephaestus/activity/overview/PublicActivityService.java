package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.activity.overview.dto.PublicActivityDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.PublicActivityPersonDetailDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.PublicActivityWorkDTO;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.settings.spi.PublicActivityPolicy;
import de.tum.cit.aet.hephaestus.core.web.CursorPageDTO;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
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
        var selected = publishedWorkspace(slug);
        return PublicActivityDTO.from(
                selected.getDisplayName(),
                workspaceQueries.scmProviderType(selected),
                selected.isPublicActivitySearchEngines(),
                people.publicPeople(
                        selected.getId(),
                        range,
                        repositoryKeys,
                        selected.isPublicActivityContributorsWithoutAccount()));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public Published<PublicActivityPersonDetailDTO> person(
            String slug, String login, ActivityPeopleRangeParams range, Set<String> repositoryKeys) {
        var workspace = publishedWorkspace(slug);
        return new Published<>(
                people.publicPerson(
                        workspace.getId(),
                        login,
                        range,
                        repositoryKeys,
                        workspace.isPublicActivityContributorsWithoutAccount()),
                workspace.isPublicActivitySearchEngines());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public Published<CursorPageDTO<PublicActivityWorkDTO>> work(
            String slug,
            String login,
            ActivityPeopleRangeParams range,
            Set<String> repositoryKeys,
            ActivityWorkFilterParams page) {
        var workspace = publishedWorkspace(slug);
        return new Published<>(
                people.publicPersonWork(
                        workspace.getId(),
                        login,
                        range,
                        repositoryKeys,
                        page,
                        workspace.isPublicActivityContributorsWithoutAccount()),
                workspace.isPublicActivitySearchEngines());
    }

    private Workspace publishedWorkspace(String slug) {
        var workspace = workspaces.findPublicActivityWorkspace(slug);
        if (!policy.allowed() || workspace.isEmpty()) {
            var exception =
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "This public activity page is not available.");
            exception.getBody().setInstance(URI.create("/public/activity"));
            throw exception;
        }
        return workspace.get();
    }

    record Published<T>(T body, boolean allowSearchEngines) {}
}
