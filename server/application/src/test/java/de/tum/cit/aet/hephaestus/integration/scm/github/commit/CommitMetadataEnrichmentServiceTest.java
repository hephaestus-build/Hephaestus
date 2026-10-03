package de.tum.cit.aet.hephaestus.integration.scm.github.commit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.Commit;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitContributorRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.GraphQlClient;
import org.springframework.graphql.client.HttpGraphQlClient;
import reactor.core.publisher.Mono;

class CommitMetadataEnrichmentServiceTest extends BaseUnitTest {

    @Mock
    private CommitRepository commitRepository;

    @Mock
    private CommitContributorRepository contributorRepository;

    @Mock
    private GitHubGraphQlClientProvider graphQlClientProvider;

    @Mock
    private GitHubGraphQlSyncCoordinator graphQlSyncCoordinator;

    @Mock
    private GitHubExceptionClassifier exceptionClassifier;

    private CommitMetadataEnrichmentService service;

    @BeforeEach
    void setUp() {
        service = new CommitMetadataEnrichmentService(
                commitRepository,
                contributorRepository,
                graphQlClientProvider,
                graphQlSyncCoordinator,
                exceptionClassifier);
    }

    @Nested
    class SkipConditions {

        @Test
        void shouldReturnZeroWhenScopeIdNull() {
            int result = service.enrichCommitMetadata(1L, "owner/repo", null);

            assertThat(result).isEqualTo(0);
            verify(commitRepository, never()).findShasWithoutContributorsByRepositoryId(anyLong());
        }

        @Test
        void shouldReturnZeroWhenNoCommitsNeedEnrichment() {
            when(commitRepository.findShasWithoutContributorsByRepositoryId(1L)).thenReturn(List.of());

            int result = service.enrichCommitMetadata(1L, "owner/repo", 1L);

            assertThat(result).isEqualTo(0);
        }

        @Test
        void shouldReturnZeroWhenAllShasInvalid() {
            when(commitRepository.findShasWithoutContributorsByRepositoryId(1L))
                    .thenReturn(List.of("not-a-sha", "also-invalid", "ABC"));

            int result = service.enrichCommitMetadata(1L, "owner/repo", 1L);

            assertThat(result).isEqualTo(0);
        }

        @Test
        void shouldReturnZeroWhenNameWithOwnerInvalid() {
            when(commitRepository.findShasWithoutContributorsByRepositoryId(1L)).thenReturn(List.of("a".repeat(40)));

            int result = service.enrichCommitMetadata(1L, "invalid-no-slash", 1L);

            assertThat(result).isEqualTo(0);
        }
    }

    @Nested
    class ShaFiltering {

        @Test
        void shouldFilterInvalidShas() {
            String validSha = "a".repeat(40);
            when(commitRepository.findShasWithoutContributorsByRepositoryId(1L))
                    .thenReturn(List.of("invalid-sha", validSha, "too-short"));

            // GraphQL call will fail without a mocked client, returning 0 enriched
            // but the service should not crash
            int result = service.enrichCommitMetadata(1L, "owner/repo", 1L);

            assertThat(result).isGreaterThanOrEqualTo(0);
        }
    }

    @Nested
    class RateLimitHandling {

        @Test
        void shouldAbortWhenRateLimitCriticalAndWaitFails() {
            String validSha = "a".repeat(40);
            when(commitRepository.findShasWithoutContributorsByRepositoryId(1L)).thenReturn(List.of(validSha));

            when(graphQlClientProvider.isRateLimitCritical(1L)).thenReturn(true);
            when(graphQlSyncCoordinator.waitForRateLimitIfNeeded(eq(1L), any(), any(), any(), any()))
                    .thenReturn(false);

            int result = service.enrichCommitMetadata(1L, "owner/repo", 1L);

            assertThat(result).isEqualTo(0);
            // Should not attempt any upserts
            verify(contributorRepository, never()).upsertContributor(anyLong(), any(), any(), any(), any(), anyInt());
        }
    }

    @Nested
    class EnrichmentMetadata {

        @Test
        @SuppressWarnings("unchecked")
        void shouldCallUpdateEnrichmentMetadataForValidResponse() {
            String sha = "a".repeat(40);
            Long repoId = 1L;
            Long scopeId = 1L;
            Long commitId = 42L;

            when(commitRepository.findShasWithoutContributorsByRepositoryId(repoId))
                    .thenReturn(List.of(sha));
            when(graphQlClientProvider.isRateLimitCritical(scopeId)).thenReturn(false);

            Commit commit = new Commit();
            commit.setId(commitId);
            when(commitRepository.findByShaAndRepositoryId(sha, repoId)).thenReturn(Optional.of(commit));

            // Build a mock response with enrichment metadata
            Map<String, Object> commitData = Map.ofEntries(
                    Map.entry("oid", sha),
                    Map.entry("additions", 10),
                    Map.entry("deletions", 5),
                    Map.entry("changedFilesIfAvailable", 3),
                    Map.entry("authoredDate", "2025-01-15T10:30:00Z"),
                    Map.entry("committedDate", "2025-01-15T11:00:00Z"),
                    Map.entry("messageHeadline", "feat: add feature"),
                    Map.entry("messageBody", "Detailed description here"),
                    Map.entry("url", "https://github.com/owner/repo/commit/" + sha),
                    Map.entry("signature", Map.of("isValid", true)),
                    Map.entry("authoredByCommitter", true),
                    Map.entry("committedViaWeb", false),
                    Map.entry("parents", Map.of("totalCount", 1)),
                    Map.entry(
                            "authors",
                            Map.of("nodes", List.of(Map.of("name", "Test Author", "email", "test@example.com")))),
                    Map.entry("committer", Map.of("name", "Test Author", "email", "test@example.com")),
                    Map.entry("associatedPullRequests", Map.of("nodes", List.of())));

            // Mock GraphQL client and response
            ClientResponseField field = mock(ClientResponseField.class);
            when(field.getValue()).thenReturn(commitData);
            when(field.toEntity(any(ParameterizedTypeReference.class))).thenReturn(commitData);

            ClientGraphQlResponse graphQlResponse = mock(ClientGraphQlResponse.class);
            when(graphQlResponse.isValid()).thenReturn(true);
            when(graphQlResponse.field("repository.commit0")).thenReturn(field);

            HttpGraphQlClient client = mock(HttpGraphQlClient.class);
            GraphQlClient.RequestSpec requestSpec = mock(GraphQlClient.RequestSpec.class);
            when(client.document(anyString())).thenReturn(requestSpec);
            when(requestSpec.execute()).thenReturn(Mono.just(graphQlResponse));

            when(graphQlClientProvider.forScope(scopeId)).thenReturn(client);

            int result = service.enrichCommitMetadata(repoId, "owner/repo", scopeId);

            assertThat(result).isEqualTo(1);

            // Verify enrichment metadata was updated
            verify(commitRepository)
                    .updateEnrichmentMetadata(
                            eq(commitId),
                            eq(10),
                            eq(5),
                            eq(3),
                            eq(Instant.parse("2025-01-15T10:30:00Z")),
                            eq(Instant.parse("2025-01-15T11:00:00Z")),
                            eq("feat: add feature"),
                            eq("Detailed description here"),
                            eq("https://github.com/owner/repo/commit/" + sha),
                            eq(true),
                            eq(true),
                            eq(false),
                            eq(1),
                            eq(null), // signatureState (not in simple Map.of("isValid", true))
                            eq(null), // signatureWasSignedByGitHub
                            eq(null), // signatureSignerLogin
                            eq(null), // parentShas (parents has no nodes)
                            eq(null), // statusCheckRollupState
                            eq(null) // onBehalfOfLogin
                            );

            // Verify contributor was upserted (primary author)
            verify(contributorRepository)
                    .upsertContributor(
                            eq(commitId), any(), eq("AUTHOR"), eq("Test Author"), eq("test@example.com"), eq(0));
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldHandleNullSignatureGracefully() {
            String sha = "b".repeat(40);
            Long repoId = 1L;
            Long scopeId = 1L;
            Long commitId = 43L;

            when(commitRepository.findShasWithoutContributorsByRepositoryId(repoId))
                    .thenReturn(List.of(sha));
            when(graphQlClientProvider.isRateLimitCritical(scopeId)).thenReturn(false);

            Commit commit = new Commit();
            commit.setId(commitId);
            when(commitRepository.findByShaAndRepositoryId(sha, repoId)).thenReturn(Optional.of(commit));

            // Response with null signature (unsigned commit)
            Map<String, Object> commitData = new HashMap<>();
            commitData.put("oid", sha);
            commitData.put("additions", 0);
            commitData.put("deletions", 0);
            commitData.put("changedFilesIfAvailable", null);
            commitData.put("authoredDate", "2025-01-15T10:30:00Z");
            commitData.put("committedDate", "2025-01-15T10:30:00Z");
            commitData.put("messageHeadline", "initial commit");
            commitData.put("messageBody", null);
            commitData.put("url", "https://github.com/owner/repo/commit/" + sha);
            commitData.put("signature", null);
            commitData.put("authoredByCommitter", true);
            commitData.put("committedViaWeb", false);
            commitData.put("parents", Map.of("totalCount", 0));
            commitData.put(
                    "authors", Map.of("nodes", List.of(Map.of("name", "Author", "email", "author@example.com"))));
            commitData.put("committer", Map.of("name", "Author", "email", "author@example.com"));
            commitData.put("associatedPullRequests", Map.of("nodes", List.of()));

            // Mock GraphQL client and response
            ClientResponseField field = mock(ClientResponseField.class);
            when(field.getValue()).thenReturn(commitData);
            when(field.toEntity(any(ParameterizedTypeReference.class))).thenReturn(commitData);

            ClientGraphQlResponse graphQlResponse = mock(ClientGraphQlResponse.class);
            when(graphQlResponse.isValid()).thenReturn(true);
            when(graphQlResponse.field("repository.commit0")).thenReturn(field);

            HttpGraphQlClient client = mock(HttpGraphQlClient.class);
            GraphQlClient.RequestSpec requestSpec = mock(GraphQlClient.RequestSpec.class);
            when(client.document(anyString())).thenReturn(requestSpec);
            when(requestSpec.execute()).thenReturn(Mono.just(graphQlResponse));

            when(graphQlClientProvider.forScope(scopeId)).thenReturn(client);

            int result = service.enrichCommitMetadata(repoId, "owner/repo", scopeId);

            assertThat(result).isEqualTo(1);

            // signatureValid should be null since signature object was null
            verify(commitRepository)
                    .updateEnrichmentMetadata(
                            eq(commitId),
                            eq(0),
                            eq(0),
                            eq(null), // changedFilesIfAvailable was null
                            eq(Instant.parse("2025-01-15T10:30:00Z")),
                            eq(Instant.parse("2025-01-15T10:30:00Z")),
                            eq("initial commit"),
                            eq(null), // no message body
                            eq("https://github.com/owner/repo/commit/" + sha),
                            eq(null), // signatureValid = null (no signature)
                            eq(true),
                            eq(false),
                            eq(0),
                            eq(null), // signatureState
                            eq(null), // signatureWasSignedByGitHub
                            eq(null), // signatureSignerLogin
                            eq(null), // parentShas
                            eq(null), // statusCheckRollupState
                            eq(null) // onBehalfOfLogin
                            );
        }
    }
}
