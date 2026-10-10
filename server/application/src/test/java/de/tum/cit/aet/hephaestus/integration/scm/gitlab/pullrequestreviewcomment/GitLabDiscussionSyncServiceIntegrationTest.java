package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreviewcomment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryScopeFilter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScopeIdResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThreadRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabUserLookup;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.dto.GitLabWebhookUser;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuecomment.GitLabIssueCommentProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuecomment.dto.GitLabNoteEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreview.GitLabReviewReconciler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreviewthread.GitLabPullRequestReviewThreadProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.GraphQlClient.RequestSpec;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

class GitLabDiscussionSyncServiceIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private PullRequestRepository pullRequests;

    @Autowired
    private PullRequestReviewRepository reviews;

    @Autowired
    private PullRequestReviewCommentRepository reviewComments;

    @Autowired
    private PullRequestReviewThreadRepository reviewThreads;

    @Autowired
    private UserRepository users;

    private long providerId;
    private long repositoryId;
    private long authorId;
    private final long workspaceId = 1;
    private final Instant at = Instant.parse("2025-01-03T10:11:12.123456Z");

    private void fixture(IdentityProviderType type) {
        String unique = UUID.randomUUID().toString();
        providerId = Objects.requireNonNull(providers
                .saveAndFlush(new IdentityProvider(type, "https://" + unique + ".example.com"))
                .getId());
        repositoryId = Objects.requireNonNull(jdbc.queryForObject("""
            INSERT INTO repository(native_id,provider_id,name,name_with_owner,has_discussions_enabled,is_private,is_archived,is_disabled,html_url,pushed_at,visibility)
            VALUES (1,?,'repo',?,false,false,false,false,'https://example.com/repo',now(),'PUBLIC') RETURNING id
            """, Long.class, providerId, "repair/" + unique));
        authorId = author(2, "author");
    }

    private long author(long nativeId, String login) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "INSERT INTO \"user\"(native_id,provider_id,login,type) VALUES (?,?,?,'USER') RETURNING id",
                Long.class,
                nativeId,
                providerId,
                login));
    }

    private long issue(String type, int number, @Nullable Long author) {
        return Objects.requireNonNull(jdbc.queryForObject(
                """
                INSERT INTO issue(issue_type,native_id,provider_id,repository_id,number,
                    author_id,created_at,state,comments_count,is_locked,is_merged,title,is_draft,additions,deletions,changed_files,commits)
                VALUES (?,?,?, ?,?,?,?,'OPEN',0,false,false,'Work',false,0,0,0,0) RETURNING id
                """, Long.class, type, number, providerId, repositoryId, number, author, Timestamp.from(at)));
    }

    @Test
    void shouldPublishLiveReviewForWebhookStarterButNotItsReply() {
        fixture(IdentityProviderType.GITLAB);
        long prId = issue("PULL_REQUEST", 1, null);
        var publisher = mock(ApplicationEventPublisher.class);
        var userService = mock(GitLabUserService.class);
        var processor = new GitLabDiffNoteWebhookProcessor(
                userService,
                users,
                mock(LabelRepository.class),
                mock(RepositoryRepository.class),
                mock(ScopeIdResolver.class),
                mock(RepositoryScopeFilter.class),
                mock(GitLabProperties.class),
                pullRequests,
                new GitLabPullRequestReviewThreadProcessor(reviewThreads, pullRequests, publisher),
                new GitLabPullRequestReviewCommentProcessor(reviewComments, publisher),
                reviewComments,
                new GitLabReviewReconciler(reviews, publisher));
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var pr = pullRequests.findById(prId).orElseThrow();
            var author = users.findById(authorId).orElseThrow();
            // The webhook user may be absent; the source lookup still supplies the stored author.
            when(userService.findOrCreateUser((GitLabWebhookUser) null, providerId))
                    .thenReturn(author);
            var context =
                    ProcessingContext.forWebhook(workspaceId, Objects.requireNonNull(pr.getRepository()), "create");
            assertThat(processor.processDiffNote(note(30, at), context)).isNotNull();
            assertThat(processor.processDiffNote(note(31, at.plusSeconds(1)), context))
                    .isNotNull();
        });
        var reviewEvent = ArgumentCaptor.forClass(ScmDomainEvent.ReviewSubmitted.class);
        verify(publisher).publishEvent(reviewEvent.capture());
        assertThat(reviewEvent.getValue().context().isWebhook()).isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pull_request_review WHERE pull_request_id=?", Integer.class, prId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pull_request_review_comment WHERE pull_request_id=? AND in_reply_to_id IS NOT NULL",
                        Integer.class,
                        prId))
                .isEqualTo(1);
    }

    private GitLabNoteEventDTO note(long id, Instant createdAt) {
        return new GitLabNoteEventDTO(
                "note",
                "note",
                null,
                null,
                new GitLabNoteEventDTO.NoteAttributes(
                        id,
                        "Review comment",
                        "MergeRequest",
                        false,
                        false,
                        Map.of("new_path", "file.java", "new_line", 1),
                        "create",
                        "https://example.com/note",
                        createdAt.toString(),
                        createdAt.toString(),
                        "abcdef1234567890",
                        "DiffNote"),
                null,
                new GitLabNoteEventDTO.EmbeddedMergeRequest(
                        1L,
                        1,
                        "Work",
                        "",
                        "opened",
                        false,
                        "feature",
                        "main",
                        "https://example.com/mr",
                        at.toString(),
                        at.toString(),
                        null));
    }

    @Test
    void shouldStoreOnlyTheThreadStarterAsAReviewWhenGitLabDiscussionIsSynced() {
        fixture(IdentityProviderType.GITLAB);
        long prId = issue("PULL_REQUEST", 1, null);
        long replyAuthorId = author(3, "reply-author");
        var clients = mock(GitLabGraphQlClientProvider.class);
        var client = mock(HttpGraphQlClient.class);
        var request = mock(RequestSpec.class);
        var response = mock(ClientGraphQlResponse.class);
        var nodes = mock(ClientResponseField.class);
        var page = mock(ClientResponseField.class);
        var responses = mock(GitLabGraphQlResponseHandler.class);
        var properties = mock(GitLabProperties.class);
        var userLookup = mock(GitLabIssueCommentProcessor.class);
        var ignoredEvents = mock(ApplicationEventPublisher.class);
        Map<String, Object> root = Map.of(
                "id",
                "gid://gitlab/DiffNote/30",
                "body",
                "Original comment",
                "createdAt",
                at.toString(),
                "author",
                Map.of("id", "gid://gitlab/User/2", "username", "author"),
                "position",
                Map.of("filePath", "file.java", "newPath", "file.java", "newLine", 1));
        Map<String, Object> reply = Map.of(
                "id",
                "gid://gitlab/DiffNote/31",
                "body",
                "Reply",
                "createdAt",
                at.plusSeconds(1).toString(),
                "author",
                Map.of("id", "gid://gitlab/User/3", "username", "reply-author"));
        Map<String, Object> discussion = Map.of(
                "id",
                "gid://gitlab/Discussion/abcdef1234567890",
                "resolved",
                false,
                "notes",
                Map.of("nodes", List.of(root, reply)));
        when(clients.forScope(workspaceId)).thenReturn(client);
        when(client.documentName("GetMergeRequestDiscussions")).thenReturn(request);
        when(request.variable(anyString(), any())).thenReturn(request);
        when(request.execute()).thenReturn(Mono.just(response));
        when(properties.graphqlTimeout()).thenReturn(Duration.ofSeconds(1));
        when(responses.handle(eq(response), anyString(), any()))
                .thenReturn(new GitLabGraphQlResponseHandler.HandleResult(
                        GitLabGraphQlResponseHandler.HandleResult.Action.CONTINUE, null));
        when(responses.isWholePage(response, "project.mergeRequest.discussions"))
                .thenReturn(true);
        when(response.field("project.mergeRequest.discussions.nodes")).thenReturn(nodes);
        when(nodes.toEntityList(Map.class)).thenReturn(List.of(discussion));
        when(response.field("project.mergeRequest.discussions.pageInfo")).thenReturn(page);
        when(page.toEntity(GitLabPageInfo.class)).thenReturn(new GitLabPageInfo(false, null));
        var service = new GitLabDiscussionSyncService(
                clients,
                responses,
                new GitLabPullRequestReviewThreadProcessor(reviewThreads, pullRequests, ignoredEvents),
                new GitLabPullRequestReviewCommentProcessor(reviewComments, ignoredEvents),
                userLookup,
                new GitLabReviewReconciler(reviews, ignoredEvents),
                properties);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var pr = pullRequests.findById(prId).orElseThrow();
            when(userLookup.findOrCreateUser(any(GitLabUserLookup.class), eq(providerId)))
                    .thenAnswer(invocation -> {
                        GitLabUserLookup lookup = invocation.getArgument(0);
                        return users.findById("author".equals(lookup.username()) ? authorId : replyAuthorId)
                                .orElseThrow();
                    });
            assertThat(service.syncDiscussionsForMergeRequest(
                            workspaceId, Objects.requireNonNull(pr.getRepository()), 1, pr))
                    .isEqualTo(2);
        });
        assertThat(jdbc.queryForList(
                        "SELECT author_id FROM pull_request_review WHERE pull_request_id=?", Long.class, prId))
                .containsExactly(authorId);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pull_request_review_comment WHERE pull_request_id=?", Long.class, prId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pull_request_review_comment WHERE pull_request_id=? AND in_reply_to_id IS NOT NULL",
                        Long.class,
                        prId))
                .isEqualTo(1);
    }
}
