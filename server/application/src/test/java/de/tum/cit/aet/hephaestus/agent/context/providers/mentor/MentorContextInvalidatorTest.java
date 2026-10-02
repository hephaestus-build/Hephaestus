package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.RepositoryRef;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for {@link MentorContextInvalidator}: event-to-cache-eviction wiring. The full
 * Spring/transaction integration is implicit (the {@code @TransactionalEventListener}
 * annotation is metadata for the runtime — invoking the method directly tests the
 * business logic).
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class MentorContextInvalidatorTest extends BaseUnitTest {

    @Mock
    CacheManager cacheManager;

    @Mock
    WorkspaceRepository workspaceRepository;

    @Mock
    Cache userCache;

    @Mock
    Cache workspaceCache;

    @Mock
    Cache authoredWorkCache;

    @InjectMocks
    MentorContextInvalidator invalidator;

    @BeforeEach
    void setUp() {
        when(cacheManager.getCache("mentor_user_context")).thenReturn(userCache);
        when(cacheManager.getCache("mentor_workspace_context")).thenReturn(workspaceCache);
        when(cacheManager.getCache("mentor_authored_work_context")).thenReturn(authoredWorkCache);
    }

    @Test
    void prUpdateEvictsAuthor() {
        when(workspaceRepository.findWorkspaceIdByRepositoryId(eq(42L))).thenReturn(Optional.of(7L));

        invalidator.onPullRequestUpdated(buildPrUpdated(42L, 9L, null));

        verify(userCache).evict(eq("7:9"));
        verify(workspaceCache).evict(eq("7:9"));
        verify(authoredWorkCache).evict(eq("7:9"));
    }

    @Test
    void prUpdateEvictsMerger() {
        when(workspaceRepository.findWorkspaceIdByRepositoryId(eq(42L))).thenReturn(Optional.of(7L));

        invalidator.onPullRequestUpdated(buildPrUpdated(42L, 9L, 11L));

        verify(userCache).evict(eq("7:9"));
        verify(userCache).evict(eq("7:11"));
    }

    @Test
    void prUpdateUnknownWorkspace() {
        when(workspaceRepository.findWorkspaceIdByRepositoryId(eq(42L))).thenReturn(Optional.empty());

        invalidator.onPullRequestUpdated(buildPrUpdated(42L, 9L, null));

        verify(userCache, never()).evict(ArgumentMatchers.any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"updated", "closed"})
    void shouldEvictOnlyTheIssueAuthorsRecordedContext(String action) {
        when(workspaceRepository.findWorkspaceIdsByRepositoryId(42L, Pageable.unpaged()))
                .thenReturn(List.of(7L, 8L));
        var caches = new ConcurrentMapCacheManager(
                "mentor_user_context", "mentor_workspace_context", "mentor_authored_work_context");
        var subject = new MentorContextInvalidator(caches, workspaceRepository, mock(PullRequestRepository.class));
        caches.getCacheNames().forEach(name -> {
            var cache = Objects.requireNonNull(caches.getCache(name));
            cache.put("7:9", "old state");
            cache.put("8:9", "old state in another monitor");
            cache.put("7:10", "another developer");
            cache.put("9:9", "unrelated workspace");
        });

        switch (action) {
            case "updated" -> subject.onIssueUpdated(buildIssueUpdated(42L, 9L, Issue.State.OPEN));
            case "closed" -> {
                var data = buildIssueUpdated(42L, 9L, Issue.State.CLOSED);
                subject.onIssueClosed(new ScmDomainEvent.IssueClosed(data.issue(), "completed", data.context()));
            }
            default -> throw new AssertionError(action);
        }

        caches.getCacheNames().forEach(name -> {
            var cache = Objects.requireNonNull(caches.getCache(name));
            assertThat(cache.get("7:9")).isNull();
            assertThat(cache.get("8:9")).isNull();
            assertThat(cache.get("7:10", String.class)).isEqualTo("another developer");
            assertThat(cache.get("9:9", String.class)).isEqualTo("unrelated workspace");
        });
    }

    private static ScmDomainEvent.PullRequestUpdated buildPrUpdated(
            long repoId, Long authorId, @Nullable Long mergedById) {
        ScmEventPayload.PullRequestData pr = new ScmEventPayload.PullRequestData(
                1L,
                42,
                "title",
                "body",
                PullRequest.State.OPEN,
                false,
                mergedById != null,
                10,
                5,
                1,
                "https://example.com/pr/42",
                new RepositoryRef(repoId, "acme/repo", "main"),
                authorId,
                Instant.now().minusSeconds(60),
                Instant.now(),
                null,
                mergedById != null ? Instant.now() : null,
                mergedById);
        return new ScmDomainEvent.PullRequestUpdated(pr, Set.of(), buildContext(repoId));
    }

    private static ScmDomainEvent.IssueUpdated buildIssueUpdated(long repoId, Long authorId, Issue.State state) {
        ScmEventPayload.IssueData issue = new ScmEventPayload.IssueData(
                1L,
                17,
                "title",
                "body",
                state,
                null,
                "https://example.com/issue/17",
                false,
                new RepositoryRef(repoId, "acme/repo", "main"),
                authorId,
                null,
                null,
                List.of(),
                List.of(),
                Instant.now(),
                Instant.now(),
                null);
        return new ScmDomainEvent.IssueUpdated(issue, Set.of(), buildContext(repoId));
    }

    private static EventContext buildContext(long repoId) {
        return new EventContext(
                UUID.randomUUID(),
                Instant.now(),
                null,
                new RepositoryRef(repoId, "acme/repo", "main"),
                DataSource.WEBHOOK,
                "synchronize",
                UUID.randomUUID().toString(),
                IdentityProviderType.GITHUB);
    }
}
