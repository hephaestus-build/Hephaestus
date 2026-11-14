package de.tum.cit.aet.hephaestus.activity.overview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityPeopleDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.PublicActivityPersonDetailDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.PublicActivityWorkDTO;
import de.tum.cit.aet.hephaestus.core.settings.spi.PublicActivityPolicy;
import de.tum.cit.aet.hephaestus.core.web.CursorPageDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceQueryService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.adapter.WorkspaceQueriesAdapter;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewScopeRepositoryRepository;
import java.net.URI;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class PublicActivityServiceTest extends BaseUnitTest {
    private static final ActivityPeopleRangeParams RANGE = new ActivityPeopleRangeParams(null, null, null);
    private static final ActivityWorkFilterParams PAGE = new ActivityWorkFilterParams(null, null, null);

    @Mock
    WorkspaceRepository repositories;

    @Mock
    WorkspaceQueryService metadata;

    @Mock
    PublicActivityPolicy policy;

    @Mock
    ActivityPeopleService people;

    PublicActivityService service;

    @BeforeEach
    void configureAnOptedInWorkspaceWithConflictingProviders() {
        var workspace = new Workspace();
        workspace.setId(7L);
        workspace.setWorkspaceSlug("public");
        workspace.setDisplayName("Public");
        workspace.setPublicActivityContributorsWithoutAccount(true);
        when(repositories.findPublicActivityWorkspace("public")).thenReturn(Optional.of(workspace));
        lenient().when(repositories.findById(7L)).thenReturn(Optional.of(workspace));
        lenient()
                .when(metadata.scmProviderType(workspace))
                .thenThrow(new IllegalStateException("Workspace has multiple active SCM providers."));
        when(policy.allowed()).thenReturn(true);
        service = new PublicActivityService(
                new WorkspaceQueriesAdapter(
                        repositories,
                        mock(RepositoryToMonitorRepository.class),
                        mock(ReviewScopeRepositoryRepository.class),
                        metadata),
                policy,
                people);
    }

    @Test
    void shouldKeepTheUniformNotFoundResponseWhenPublicationIsOffAndProvidersConflict() {
        when(policy.allowed()).thenReturn(false);
        assertThatThrownBy(() -> service.page("public", RANGE, Set.of()))
                .isInstanceOfSatisfying(ResponseStatusException.class, unavailable -> {
                    assertThat(unavailable.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(unavailable.getBody().getInstance()).isEqualTo(URI.create("/public/activity"));
                });
    }

    @Test
    void shouldReturnThePersonWhenProvidersConflict() {
        var person = mock(PublicActivityPersonDetailDTO.class);
        when(people.publicPerson(7L, "ada", RANGE, Set.of(), true)).thenReturn(person);
        var result = service.person("public", "ada", RANGE, Set.of());
        assertThat(result.body()).isSameAs(person);
        assertThat(result.allowSearchEngines()).isFalse();
    }

    @Test
    void shouldReturnThePersonsWorkWhenProvidersConflict() {
        CursorPageDTO<PublicActivityWorkDTO> work = CursorPageDTO.empty();
        when(people.publicPersonWork(7L, "ada", RANGE, Set.of(), PAGE, true)).thenReturn(work);
        var result = service.work("public", "ada", RANGE, Set.of(), PAGE);
        assertThat(result.body()).isEqualTo(work);
        assertThat(result.allowSearchEngines()).isFalse();
    }

    @Test
    void shouldStillResolveTheProviderForThePublishedPage() {
        when(people.publicPeople(7L, RANGE, Set.of(), true))
                .thenReturn(new ActivityPeopleService.PublicPeople(mock(ActivityPeopleDTO.class), 0));
        assertThatThrownBy(() -> service.page("public", RANGE, Set.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Workspace has multiple active SCM providers.");
    }
}
