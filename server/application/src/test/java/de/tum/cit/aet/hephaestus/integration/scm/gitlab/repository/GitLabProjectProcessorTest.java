package de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabProjectResponse;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

@Tag("unit")
class GitLabProjectProcessorTest extends BaseUnitTest {

    private static final Long PROVIDER_ID = 2L;

    @Mock
    private RepositoryRepository repositoryRepository;

    private GitLabProjectProcessor processor;
    private IdentityProvider gitLabProvider;

    @BeforeEach
    void setUp() {
        processor = new GitLabProjectProcessor(repositoryRepository);

        gitLabProvider = new IdentityProvider();
        gitLabProvider.setId(PROVIDER_ID);
        gitLabProvider.setType(IdentityProviderType.GITLAB);
        gitLabProvider.setServerUrl("https://gitlab.com");
    }

    @Nested
    class ProcessGraphQlResponse {

        @BeforeEach
        void stubSave() {
            // Lenient: null/invalid input tests return early before reaching save()
            lenient().when(repositoryRepository.save(any(Repository.class))).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test
        void validProject_mapsAllFields() {
            var repoInfo = new GitLabProjectResponse.RepositoryInfo("develop");
            var project = new GitLabProjectResponse(
                    "gid://gitlab/Project/123",
                    "my-org/my-project",
                    "my-project",
                    "https://gitlab.com/my-org/my-project",
                    "A cool project",
                    "public",
                    false,
                    "2024-01-15T10:30:00Z",
                    "2024-06-20T14:00:00Z",
                    null,
                    repoInfo);

            Organization org = new Organization();
            org.setId(42L);

            when(repositoryRepository.findByNativeIdAndProviderId(123L, PROVIDER_ID))
                    .thenReturn(Optional.empty());

            Repository result = processor.processGraphQlResponse(project, org, gitLabProvider);

            assertThat(result).isNotNull();
            assertNotNull(result);
            assertThat(result.getNativeId()).isEqualTo(123L);
            assertThat(result.getName()).isEqualTo("my-project");
            assertThat(result.getNameWithOwner()).isEqualTo("my-org/my-project");
            assertThat(result.getHtmlUrl()).isEqualTo("https://gitlab.com/my-org/my-project");
            assertNotNull(result);
            assertThat(result.getDescription()).isEqualTo("A cool project");
            assertThat(result.getVisibility()).isEqualTo(Repository.Visibility.PUBLIC);
            assertThat(result.isPrivate()).isFalse();
            assertThat(result.isArchived()).isFalse();
            assertNotNull(result);
            assertThat(result.isDisabled()).isFalse();
            assertThat(result.getDefaultBranch()).isEqualTo("develop");
            assertThat(result.getOrganization()).isEqualTo(org);
            assertNotNull(result);
            assertThat(result.getCreatedAt()).isEqualTo(Instant.parse("2024-01-15T10:30:00Z"));
            assertThat(result.getPushedAt()).isEqualTo(Instant.parse("2024-06-20T14:00:00Z"));
            assertThat(result.getUpdatedAt()).isEqualTo(Instant.parse("2024-06-20T14:00:00Z"));
            // lastSyncAt is set AFTER issue sync completes (in WorkspaceActivationService),
            // not during project discovery
            assertNotNull(result);
            assertThat(result.getLastSyncAt()).isNull();
        }

        @Test
        void privateVisibility_setsIsPrivateTrue() {
            var project = createMinimalProject("private");
            when(repositoryRepository.findByNativeIdAndProviderId(123L, PROVIDER_ID))
                    .thenReturn(Optional.empty());

            Repository result = processor.processGraphQlResponse(project, null, gitLabProvider);

            assertNotNull(result);
            assertThat(result.getVisibility()).isEqualTo(Repository.Visibility.PRIVATE);
            assertThat(result.isPrivate()).isTrue();
        }

        @Test
        void internalVisibility_mapsCorrectly() {
            var project = createMinimalProject("internal");
            when(repositoryRepository.findByNativeIdAndProviderId(123L, PROVIDER_ID))
                    .thenReturn(Optional.empty());

            Repository result = processor.processGraphQlResponse(project, null, gitLabProvider);

            assertNotNull(result);
            assertThat(result.getVisibility()).isEqualTo(Repository.Visibility.INTERNAL);
            assertThat(result.isPrivate()).isFalse();
        }

        @Test
        void nullRootRef_fallsBackToMain() {
            var project = new GitLabProjectResponse(
                    "gid://gitlab/Project/123",
                    "org/project",
                    "project",
                    "https://gitlab.com/org/project",
                    null,
                    "public",
                    false,
                    null,
                    null,
                    null,
                    null // no repository info
                    );
            when(repositoryRepository.findByNativeIdAndProviderId(123L, PROVIDER_ID))
                    .thenReturn(Optional.empty());

            Repository result = processor.processGraphQlResponse(project, null, gitLabProvider);

            assertNotNull(result);
            assertThat(result.getDefaultBranch()).isEqualTo("main");
        }

        @Test
        void archivedProject_setsFlag() {
            var project = new GitLabProjectResponse(
                    "gid://gitlab/Project/123",
                    "org/project",
                    "project",
                    "https://gitlab.com/org/project",
                    null,
                    "public",
                    true, // archived
                    null,
                    null,
                    null,
                    null);
            when(repositoryRepository.findByNativeIdAndProviderId(123L, PROVIDER_ID))
                    .thenReturn(Optional.empty());

            Repository result = processor.processGraphQlResponse(project, null, gitLabProvider);

            assertNotNull(result);
            assertThat(result.isArchived()).isTrue();
        }

        @Test
        void existingEntity_isUpdated() {
            var project = createMinimalProject("public");
            Repository existing = new Repository();
            existing.setNativeId(123L);
            existing.setName("old-name");
            when(repositoryRepository.findByNativeIdAndProviderId(123L, PROVIDER_ID))
                    .thenReturn(Optional.of(existing));

            Repository result = processor.processGraphQlResponse(project, null, gitLabProvider);

            assertNotNull(result);
            assertThat(result.getName()).isEqualTo("project");
            // Verify save was called (not insert)
            ArgumentCaptor<Repository> captor = ArgumentCaptor.forClass(Repository.class);
            verify(repositoryRepository).save(captor.capture());
            assertThat(captor.getValue().getNativeId()).isEqualTo(123L);
        }

        @Test
        void nullResponse_returnsNull() {
            assertThat(processor.processGraphQlResponse(null, null, gitLabProvider))
                    .isNull();
            verify(repositoryRepository, never()).save(any());
        }

        @Test
        void nullWebUrl_returnsNull() {
            var project = new GitLabProjectResponse(
                    "gid://gitlab/Project/123",
                    "org/project",
                    "project",
                    null, // null webUrl
                    null,
                    "public",
                    false,
                    null,
                    null,
                    null,
                    null);
            assertThat(processor.processGraphQlResponse(project, null, gitLabProvider))
                    .isNull();
            verify(repositoryRepository, never()).save(any());
        }

        @Test
        void malformedCreatedAt_doesNotOverwriteExisting() {
            var project = new GitLabProjectResponse(
                    "gid://gitlab/Project/123",
                    "org/project",
                    "project",
                    "https://gitlab.com/org/project",
                    null,
                    "public",
                    false,
                    "not-a-date", // malformed
                    "2024-06-01T10:00:00Z",
                    null,
                    new GitLabProjectResponse.RepositoryInfo("main"));

            Instant existingCreatedAt = Instant.parse("2024-01-01T00:00:00Z");
            Repository existing = new Repository();
            existing.setNativeId(123L);
            existing.setCreatedAt(existingCreatedAt);
            when(repositoryRepository.findByNativeIdAndProviderId(123L, PROVIDER_ID))
                    .thenReturn(Optional.of(existing));

            Repository result = processor.processGraphQlResponse(project, null, gitLabProvider);

            assertThat(result).isNotNull();
            assertNotNull(result);
            assertThat(result.getCreatedAt()).isEqualTo(existingCreatedAt);
        }

        @Test
        void nullFullPath_returnsNull() {
            var project = new GitLabProjectResponse(
                    "gid://gitlab/Project/123",
                    null,
                    "project",
                    "https://gitlab.com/org/project",
                    null,
                    "public",
                    false,
                    null,
                    null,
                    null,
                    null);
            assertThat(processor.processGraphQlResponse(project, null, gitLabProvider))
                    .isNull();
            verify(repositoryRepository, never()).save(any());
        }

        @Test
        void invalidGid_returnsNull() {
            var project = new GitLabProjectResponse(
                    "not-a-valid-gid",
                    "org/project",
                    "project",
                    "https://gitlab.com/org/project",
                    null,
                    "public",
                    false,
                    null,
                    null,
                    null,
                    null);
            assertThat(processor.processGraphQlResponse(project, null, gitLabProvider))
                    .isNull();
            verify(repositoryRepository, never()).save(any());
        }

        private GitLabProjectResponse createMinimalProject(String visibility) {
            return new GitLabProjectResponse(
                    "gid://gitlab/Project/123",
                    "org/project",
                    "project",
                    "https://gitlab.com/org/project",
                    null,
                    visibility,
                    false,
                    null,
                    null,
                    null,
                    new GitLabProjectResponse.RepositoryInfo("main"));
        }
    }

    @Nested
    class MapVisibility {

        @Test
        void allVisibilityStrings() {
            assertThat(GitLabProjectProcessor.mapVisibility("public")).isEqualTo(Repository.Visibility.PUBLIC);
            assertThat(GitLabProjectProcessor.mapVisibility("private")).isEqualTo(Repository.Visibility.PRIVATE);
            assertThat(GitLabProjectProcessor.mapVisibility("internal")).isEqualTo(Repository.Visibility.INTERNAL);
            assertThat(GitLabProjectProcessor.mapVisibility("PUBLIC")).isEqualTo(Repository.Visibility.PUBLIC);
            assertThat(GitLabProjectProcessor.mapVisibility(null)).isEqualTo(Repository.Visibility.UNKNOWN);
            assertThat(GitLabProjectProcessor.mapVisibility("unknown-value")).isEqualTo(Repository.Visibility.UNKNOWN);
        }
    }
}
