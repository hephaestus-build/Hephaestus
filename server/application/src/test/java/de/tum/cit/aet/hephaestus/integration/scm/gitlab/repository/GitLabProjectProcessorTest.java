package de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryReadersProvider.ReadersRead;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryObservationStore;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryObservationStore.Kind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryObservationStore.Observation;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabProjectResponse;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

@Tag("unit")
class GitLabProjectProcessorTest {
    private static final ReadersRead UNMONITORED = new ReadersRead(1L, null, 123L, 2L, -1L, Instant.EPOCH);

    @ParameterizedTest
    @CsvSource({"public,METADATA,", "private,PRIVATE,PRIVATE", "internal,PRIVATE,INTERNAL", "unknown,PRIVATE,UNKNOWN"})
    void submitsTheProviderFieldsWithTheOriginalRead(
            String visibility, Kind kind, Repository.@Nullable Visibility expectedVisibility) {
        var store = mock(RepositoryObservationStore.class);
        var processor = new GitLabProjectProcessor(store);
        var provider = TestEntities.gitProvider(2L, IdentityProviderType.GITLAB);
        var project = new GitLabProjectResponse(
                "gid://gitlab/Project/123",
                "course/project",
                "project",
                "https://gitlab.com/course/project",
                "summary",
                visibility,
                true,
                "2024-01-15T10:30:00Z",
                "2024-06-20T14:00:00Z",
                null,
                new GitLabProjectResponse.RepositoryInfo("develop"));

        processor.processGraphQlResponse(project, null, provider, UNMONITORED);

        var observation = ArgumentCaptor.forClass(Observation.class);
        verify(store).observe(eq(UNMONITORED), observation.capture());
        assertThat(observation.getValue().kind()).isEqualTo(kind);
        assertThat(observation.getValue().visibility()).isEqualTo(expectedVisibility);
        assertThat(observation.getValue().patch())
                .containsEntry("name_with_owner", "course/project")
                .containsEntry("description", "summary")
                .containsEntry("default_branch", "develop")
                .containsEntry("created_at", "2024-01-15T10:30:00Z")
                .containsEntry("pushed_at", "2024-06-20T14:00:00Z")
                .containsEntry("is_archived", true);
    }

    @Test
    void rejectsMissingProjectAndMismatchedProvider() {
        var store = mock(RepositoryObservationStore.class);
        var processor = new GitLabProjectProcessor(store);
        var other = TestEntities.gitProvider(3L, IdentityProviderType.GITLAB);
        var project = new GitLabProjectResponse(
                "gid://gitlab/Project/123",
                "course/project",
                "project",
                "https://gitlab.com/course/project",
                null,
                "public",
                false,
                null,
                null,
                null,
                null);

        assertThat(processor.processGraphQlResponse(null, null, other, UNMONITORED))
                .isNull();
        assertThat(processor.processGraphQlResponse(project, null, other, UNMONITORED))
                .isNull();
        verifyNoInteractions(store);
    }
}
