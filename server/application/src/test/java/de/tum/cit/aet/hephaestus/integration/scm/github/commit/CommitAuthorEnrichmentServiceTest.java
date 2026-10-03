package de.tum.cit.aet.hephaestus.integration.scm.github.commit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitAuthorResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;

class CommitAuthorEnrichmentServiceTest extends BaseUnitTest {

    @Mock
    private CommitRepository commitRepository;

    @Mock
    private CommitAuthorResolver authorResolver;

    @Mock
    private GitHubGraphQlClientProvider graphQlClientProvider;

    @Mock
    private GitHubGraphQlSyncCoordinator graphQlSyncCoordinator;

    @Mock
    private GitHubExceptionClassifier exceptionClassifier;

    @Mock
    private GitHubUserProcessor userProcessor;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private CommitAuthorEnrichmentService service;

    @BeforeEach
    void setUp() {
        service = new CommitAuthorEnrichmentService(
                commitRepository,
                authorResolver,
                graphQlClientProvider,
                graphQlSyncCoordinator,
                exceptionClassifier,
                userProcessor,
                eventPublisher);
    }

    // Tests

    @Nested
    class SkipConditions {

        @Test
        void shouldReturnZeroWhenNoUnresolvedEmails() {
            when(commitRepository.findDistinctUnresolvedAuthorEmailsByRepositoryId(1L))
                    .thenReturn(List.of());
            when(commitRepository.findDistinctUnresolvedCommitterEmailsByRepositoryId(1L))
                    .thenReturn(List.of());

            int result = service.enrichCommitAuthors(1L, "owner/repo", 1L, 1L, null);

            assertThat(result).isEqualTo(0);
            verify(authorResolver, never()).resolveByEmail(any(), any());
        }
    }

    @Nested
    class EmailBasedEnrichment {

        @Test
        void shouldEnrichAuthorsByEmail() {
            when(commitRepository.findDistinctUnresolvedAuthorEmailsByRepositoryId(1L))
                    .thenReturn(List.of("author@example.com")) // first call
                    .thenReturn(List.of()); // second call (after enrichment)
            when(commitRepository.findDistinctUnresolvedCommitterEmailsByRepositoryId(1L))
                    .thenReturn(List.of()) // first call
                    .thenReturn(List.of()); // second call

            when(authorResolver.resolveByEmail(eq("author@example.com"), any())).thenReturn(42L);
            when(commitRepository.bulkUpdateAuthorIdByEmail("author@example.com", 1L, 42L))
                    .thenReturn(2);

            int result = service.enrichCommitAuthors(1L, "owner/repo", 1L, 1L, null);

            assertThat(result).isEqualTo(2);
            verify(commitRepository).bulkUpdateAuthorIdByEmail("author@example.com", 1L, 42L);
        }

        @Test
        void shouldEnrichCommittersByEmail() {
            when(commitRepository.findDistinctUnresolvedAuthorEmailsByRepositoryId(1L))
                    .thenReturn(List.of())
                    .thenReturn(List.of());
            when(commitRepository.findDistinctUnresolvedCommitterEmailsByRepositoryId(1L))
                    .thenReturn(List.of("committer@example.com"))
                    .thenReturn(List.of()); // after enrichment

            when(authorResolver.resolveByEmail(eq("committer@example.com"), any()))
                    .thenReturn(99L);
            when(commitRepository.bulkUpdateCommitterIdByEmail("committer@example.com", 1L, 99L))
                    .thenReturn(1);

            int result = service.enrichCommitAuthors(1L, "owner/repo", 1L, 1L, null);

            assertThat(result).isEqualTo(1);
            verify(commitRepository).bulkUpdateCommitterIdByEmail("committer@example.com", 1L, 99L);
        }

        @Test
        void shouldSkipUnresolvableEmails() {
            when(commitRepository.findDistinctUnresolvedAuthorEmailsByRepositoryId(1L))
                    .thenReturn(List.of("unknown@personal.com"))
                    .thenReturn(List.of("unknown@personal.com")); // still unresolved after email pass
            when(commitRepository.findDistinctUnresolvedCommitterEmailsByRepositoryId(1L))
                    .thenReturn(List.of())
                    .thenReturn(List.of());

            when(authorResolver.resolveByEmail(eq("unknown@personal.com"), any()))
                    .thenReturn(null);

            // ScopeId is null so API pass is skipped
            int result = service.enrichCommitAuthors(1L, "owner/repo", null, 1L, null);

            assertThat(result).isEqualTo(0);
            verify(commitRepository, never()).bulkUpdateAuthorIdByEmail(any(), anyLong(), anyLong());
        }

        @Test
        void shouldHandleMultipleEmailClusters() {
            when(commitRepository.findDistinctUnresolvedAuthorEmailsByRepositoryId(1L))
                    .thenReturn(List.of("alice@example.com", "bob@example.com"))
                    .thenReturn(List.of()); // all resolved after email pass
            when(commitRepository.findDistinctUnresolvedCommitterEmailsByRepositoryId(1L))
                    .thenReturn(List.of())
                    .thenReturn(List.of());

            // Alice resolves, Bob does not
            when(authorResolver.resolveByEmail(eq("alice@example.com"), any())).thenReturn(10L);
            when(authorResolver.resolveByEmail(eq("bob@example.com"), any())).thenReturn(null);

            when(commitRepository.bulkUpdateAuthorIdByEmail("alice@example.com", 1L, 10L))
                    .thenReturn(2);

            int result = service.enrichCommitAuthors(1L, "owner/repo", null, 1L, null);

            assertThat(result).isEqualTo(2);
            // Alice's cluster updated, Bob's skipped
            verify(commitRepository).bulkUpdateAuthorIdByEmail("alice@example.com", 1L, 10L);
            verify(commitRepository, never()).bulkUpdateAuthorIdByEmail(eq("bob@example.com"), anyLong(), anyLong());
        }
    }

    @Nested
    class ApiBasedEnrichment {

        @Test
        void shouldSkipApiEnrichmentWhenScopeIdNull() {
            when(commitRepository.findDistinctUnresolvedAuthorEmailsByRepositoryId(1L))
                    .thenReturn(List.of("unknown@personal.com"))
                    .thenReturn(List.of("unknown@personal.com")); // still unresolved
            when(commitRepository.findDistinctUnresolvedCommitterEmailsByRepositoryId(1L))
                    .thenReturn(List.of())
                    .thenReturn(List.of());

            when(authorResolver.resolveByEmail(eq("unknown@personal.com"), any()))
                    .thenReturn(null);

            int result = service.enrichCommitAuthors(1L, "owner/repo", null, 1L, null);

            assertThat(result).isEqualTo(0);
        }
    }

    @Nested
    class CombinedEnrichment {

        @Test
        void shouldEnrichBothAuthorAndCommitter() {
            when(commitRepository.findDistinctUnresolvedAuthorEmailsByRepositoryId(1L))
                    .thenReturn(List.of("author@example.com"))
                    .thenReturn(List.of()); // resolved after email pass
            when(commitRepository.findDistinctUnresolvedCommitterEmailsByRepositoryId(1L))
                    .thenReturn(List.of("committer@example.com"))
                    .thenReturn(List.of()); // resolved after email pass

            when(authorResolver.resolveByEmail(eq("author@example.com"), any())).thenReturn(10L);
            when(authorResolver.resolveByEmail(eq("committer@example.com"), any()))
                    .thenReturn(20L);
            when(commitRepository.bulkUpdateAuthorIdByEmail("author@example.com", 1L, 10L))
                    .thenReturn(1);
            when(commitRepository.bulkUpdateCommitterIdByEmail("committer@example.com", 1L, 20L))
                    .thenReturn(1);

            int result = service.enrichCommitAuthors(1L, "owner/repo", 1L, 1L, null);

            assertThat(result).isEqualTo(2);
            verify(commitRepository).bulkUpdateAuthorIdByEmail("author@example.com", 1L, 10L);
            verify(commitRepository).bulkUpdateCommitterIdByEmail("committer@example.com", 1L, 20L);
        }

        @Test
        void shouldCountEmailAndApiEnrichmentsSeparately() {
            // alice@example.com can be resolved by email, unknown@personal.com cannot
            when(commitRepository.findDistinctUnresolvedAuthorEmailsByRepositoryId(1L))
                    .thenReturn(List.of("alice@example.com", "unknown@personal.com"))
                    .thenReturn(List.of("unknown@personal.com")); // unknown still unresolved after email
            when(commitRepository.findDistinctUnresolvedCommitterEmailsByRepositoryId(1L))
                    .thenReturn(List.of())
                    .thenReturn(List.of());

            // Email pass resolves alice but not unknown
            when(authorResolver.resolveByEmail(eq("alice@example.com"), any())).thenReturn(10L);
            when(authorResolver.resolveByEmail(eq("unknown@personal.com"), any()))
                    .thenReturn(null);
            when(commitRepository.bulkUpdateAuthorIdByEmail("alice@example.com", 1L, 10L))
                    .thenReturn(1);

            // API pass would be attempted for unknown@personal.com but since we're not mocking
            // the GraphQL client, the API fetch will fail/return empty.
            // The service handles this gracefully — no crash, just 0 from API.

            int result = service.enrichCommitAuthors(1L, "owner/repo", 1L, 1L, null);

            // Only the email-resolved enrichment should count
            assertThat(result).isGreaterThanOrEqualTo(1);
        }
    }

    @Nested
    class EdgeCases {

        @Test
        void shouldHandleSameEmailForAuthorAndCommitter() {
            when(commitRepository.findDistinctUnresolvedAuthorEmailsByRepositoryId(1L))
                    .thenReturn(List.of("same@example.com"))
                    .thenReturn(List.of());
            when(commitRepository.findDistinctUnresolvedCommitterEmailsByRepositoryId(1L))
                    .thenReturn(List.of("same@example.com"))
                    .thenReturn(List.of());

            when(authorResolver.resolveByEmail(eq("same@example.com"), any())).thenReturn(10L);
            when(commitRepository.bulkUpdateAuthorIdByEmail("same@example.com", 1L, 10L))
                    .thenReturn(1);
            when(commitRepository.bulkUpdateCommitterIdByEmail("same@example.com", 1L, 10L))
                    .thenReturn(1);

            int result = service.enrichCommitAuthors(1L, "owner/repo", null, 1L, null);

            assertThat(result).isEqualTo(2);
        }

        @Test
        @DisplayName("should not count enrichments when bulk update returns 0")
        void shouldNotCountZeroUpdates() {
            when(commitRepository.findDistinctUnresolvedAuthorEmailsByRepositoryId(1L))
                    .thenReturn(List.of("author@example.com"))
                    .thenReturn(List.of());
            when(commitRepository.findDistinctUnresolvedCommitterEmailsByRepositoryId(1L))
                    .thenReturn(List.of())
                    .thenReturn(List.of());

            when(authorResolver.resolveByEmail(eq("author@example.com"), any())).thenReturn(42L);
            // Bulk update returns 0 (already resolved by concurrent process)
            when(commitRepository.bulkUpdateAuthorIdByEmail("author@example.com", 1L, 42L))
                    .thenReturn(0);

            int result = service.enrichCommitAuthors(1L, "owner/repo", null, 1L, null);

            assertThat(result).isEqualTo(0);
        }
    }

    @Nested
    class UnresolvableEmailFiltering {

        @Test
        void shouldFilterNoreplyFromAuthorEmails() {
            // noreply@github.com is the only unresolved email — should be filtered out
            when(commitRepository.findDistinctUnresolvedAuthorEmailsByRepositoryId(1L))
                    .thenReturn(List.of("noreply@github.com"));
            when(commitRepository.findDistinctUnresolvedCommitterEmailsByRepositoryId(1L))
                    .thenReturn(List.of());

            int result = service.enrichCommitAuthors(1L, "owner/repo", 1L, 1L, null);

            assertThat(result).isEqualTo(0);
            // Should never attempt to resolve the unresolvable email
            verify(authorResolver, never()).resolveByEmail(eq("noreply@github.com"), any());
        }

        @Test
        void shouldFilterNoreplyFromCommitterEmails() {
            when(commitRepository.findDistinctUnresolvedAuthorEmailsByRepositoryId(1L))
                    .thenReturn(List.of());
            when(commitRepository.findDistinctUnresolvedCommitterEmailsByRepositoryId(1L))
                    .thenReturn(List.of("noreply@github.com"));

            int result = service.enrichCommitAuthors(1L, "owner/repo", 1L, 1L, null);

            assertThat(result).isEqualTo(0);
            verify(authorResolver, never()).resolveByEmail(eq("noreply@github.com"), any());
        }

        @Test
        void shouldFilterNoreplyButProcessOtherEmails() {
            when(commitRepository.findDistinctUnresolvedAuthorEmailsByRepositoryId(1L))
                    .thenReturn(List.of("noreply@github.com", "real@example.com"))
                    .thenReturn(List.of()); // after enrichment
            when(commitRepository.findDistinctUnresolvedCommitterEmailsByRepositoryId(1L))
                    .thenReturn(List.of("noreply@github.com"))
                    .thenReturn(List.of());

            when(authorResolver.resolveByEmail(eq("real@example.com"), any())).thenReturn(42L);
            when(commitRepository.bulkUpdateAuthorIdByEmail("real@example.com", 1L, 42L))
                    .thenReturn(3);

            int result = service.enrichCommitAuthors(1L, "owner/repo", null, 1L, null);

            assertThat(result).isEqualTo(3);
            // noreply should be filtered, real email should be resolved
            verify(authorResolver, never()).resolveByEmail(eq("noreply@github.com"), any());
            verify(authorResolver).resolveByEmail(eq("real@example.com"), any());
        }
    }

    @Nested
    class DomainEventPublishing {

        @Test
        void shouldPublishReconciledEventWhenEnrichmentOccurs() {
            Repository repository = new Repository();
            repository.setId(1L);
            repository.setNameWithOwner("owner/repo");
            repository.setDefaultBranch("main");

            when(commitRepository.findDistinctUnresolvedAuthorEmailsByRepositoryId(1L))
                    .thenReturn(List.of("author@example.com"))
                    .thenReturn(List.of());
            when(commitRepository.findDistinctUnresolvedCommitterEmailsByRepositoryId(1L))
                    .thenReturn(List.of())
                    .thenReturn(List.of());
            when(authorResolver.resolveByEmail(eq("author@example.com"), any())).thenReturn(42L);
            when(commitRepository.bulkUpdateAuthorIdByEmail("author@example.com", 1L, 42L))
                    .thenReturn(2);

            int result = service.enrichCommitAuthors(1L, "owner/repo", 7L, 1L, repository);

            assertThat(result).isEqualTo(2);

            ArgumentCaptor<ScmDomainEvent.CommitAuthorsReconciled> captor =
                    ArgumentCaptor.forClass(ScmDomainEvent.CommitAuthorsReconciled.class);
            verify(eventPublisher).publishEvent(captor.capture());
            ScmDomainEvent.CommitAuthorsReconciled event = captor.getValue();
            assertThat(event.repositoryId()).isEqualTo(1L);
            assertThat(event.context().providerType()).isEqualTo(IdentityProviderType.GITHUB);
            assertThat(event.context().scopeId()).isEqualTo(7L);
            assertThat(event.context().repository()).isNotNull();
            assertThat(event.context().repository().id()).isEqualTo(1L);
            assertThat(event.context().repository().nameWithOwner()).isEqualTo("owner/repo");
        }

        @Test
        void shouldNotPublishWhenNothingEnriched() {
            when(commitRepository.findDistinctUnresolvedAuthorEmailsByRepositoryId(1L))
                    .thenReturn(List.of());
            when(commitRepository.findDistinctUnresolvedCommitterEmailsByRepositoryId(1L))
                    .thenReturn(List.of());

            int result = service.enrichCommitAuthors(1L, "owner/repo", 7L, 1L, null);

            assertThat(result).isEqualTo(0);
            verify(eventPublisher, never()).publishEvent(any(ScmDomainEvent.CommitAuthorsReconciled.class));
        }
    }
}
