package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.activity.overview.dto.PublicActivityDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.PublicActivityPersonDetailDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.PublicActivityWorkDTO;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.settings.spi.PublicActivityPolicy;
import de.tum.cit.aet.hephaestus.core.web.CursorPageDTO;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries.PublishedWorkspace;
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
    private final WorkspaceQueries workspaces;
    private final PublicActivityPolicy policy;
    private final ActivityPeopleService people;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public PublicActivityDTO page(String slug, ActivityPeopleRangeParams range, Set<String> repositoryKeys) {
        var selected = publishedWorkspace(slug);
        var publicPeople =
                people.publicPeople(selected.id(), range, repositoryKeys, selected.contributorsWithoutAccount());
        return PublicActivityDTO.from(
                selected.displayName(),
                workspaces.scmProviderType(selected.id()),
                selected.allowSearchEngines(),
                publicPeople.people(),
                publicPeople.unlistedContributors());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public Published<PublicActivityPersonDetailDTO> person(
            String slug, String login, ActivityPeopleRangeParams range, Set<String> repositoryKeys) {
        var workspace = publishedWorkspace(slug);
        return new Published<>(
                people.publicPerson(
                        workspace.id(), login, range, repositoryKeys, workspace.contributorsWithoutAccount()),
                workspace.allowSearchEngines());
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
                        workspace.id(), login, range, repositoryKeys, page, workspace.contributorsWithoutAccount()),
                workspace.allowSearchEngines());
    }

    private PublishedWorkspace publishedWorkspace(String slug) {
        var workspace = workspaces.publicActivityWorkspace(slug);
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
