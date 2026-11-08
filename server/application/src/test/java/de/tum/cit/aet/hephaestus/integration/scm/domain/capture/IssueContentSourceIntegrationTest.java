package de.tum.cit.aet.hephaestus.integration.scm.domain.capture;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureContribution;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.AuthorAssociation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.IssueEvidenceVersion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.ItemFixtures;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Transactional
class IssueContentSourceIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private IssueContentSource source;

    @Autowired
    private IssueRepository issues;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private IssueCommentRepository comments;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private IssueEvidenceVersion revisions;

    @Autowired
    private JsonMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldKeepAnUnreadIssueDiscussionPartialWhenTheProviderOmitsItsCount(IdentityProviderType providerType) {
        var workspace =
                workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("issue-coverage-" + UUID.randomUUID()));
        var provider = providers.saveAndFlush(
                new IdentityProvider(providerType, "https://" + UUID.randomUUID() + ".example.com"));
        var repository = new Repository();
        repository.setProvider(provider);
        repository.setNativeId(1L);
        repository.setName("repo");
        repository.setNameWithOwner("example/repo");
        repository.setHtmlUrl("https://example.com/repo");
        repository.setPushedAt(Instant.now());
        repositories.saveAndFlush(repository);
        connect(workspace, providerType, provider.getServerUrl());
        monitors.saveAndFlush(WorkspaceTestFixtures.repositoryMonitor(
                workspace, repository.getNameWithOwner(), repository.getNativeId()));
        var otherWorkspace =
                workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("other-coverage-" + UUID.randomUUID()));
        connect(otherWorkspace, providerType, "https://" + UUID.randomUUID() + ".example.com");
        monitors.saveAndFlush(WorkspaceTestFixtures.repositoryMonitor(
                otherWorkspace, repository.getNameWithOwner(), repository.getNativeId()));
        Instant now = Instant.now();
        upsert(repository, null, now);
        var issue = issues.findByRepositoryIdAndNumber(repository.getId(), 1).orElseThrow();
        assertThat(issue.getCommentsCount()).isNull();
        assertThat(issues.existsUnknownCommentCount(workspace.getId(), repository.getId()))
                .isTrue();
        assertThat(issues.existsUnknownCommentCount(otherWorkspace.getId(), repository.getId()))
                .isFalse();
        assertThat(jdbc.queryForObject(
                        "SELECT discussion_read_whole FROM issue WHERE id = ?", Boolean.class, issue.getId()))
                .isNull();
        var capture = capture(issue, workspace.getId(), providerType);

        assertThat(capture.contentStates()).containsEntry(IssueContentSource.COMMENTS, SourceContentState.EMPTY);
        assertThat(capture.completeness()).containsEntry(IssueContentSource.COMMENTS, SourceCompleteness.PARTIAL);
        assertThat(capture.captureLimitations())
                .containsEntry(IssueContentSource.COMMENTS, List.of("COMMENT_COUNT_UNKNOWN"));

        upsert(repository, 0, now.plusSeconds(1));
        var knownZero = issues.findById(issue.getId()).orElseThrow();
        assertThat(knownZero.getCommentsCount()).isZero();
        assertThat(issues.existsUnknownCommentCount(workspace.getId(), repository.getId()))
                .isFalse();
        int number = 2;
        for (Issue excluded : List.of(new PullRequest(), new Issue())) {
            excluded.setProvider(provider);
            excluded.setRepository(repository);
            excluded.setNativeId((long) number);
            excluded.setNumber(number++);
            excluded.setTitle("Other work");
            excluded.setState(Issue.State.OPEN);
            excluded.setCreatedAt(now);
            excluded.setUpdatedAt(now);
            if (!(excluded instanceof PullRequest)) excluded.setDeletedAt(now);
            issues.saveAndFlush(excluded);
            assertThat(issues.existsUnknownCommentCount(workspace.getId(), repository.getId()))
                    .isFalse();
        }

        var empty = capture(knownZero, workspace.getId(), providerType);
        assertThat(empty.completeness()).containsEntry(IssueContentSource.COMMENTS, SourceCompleteness.COMPLETE);
        assertThat(empty.captureLimitations()).isEmpty();

        upsert(repository, null, now.plusSeconds(2));
        assertThat(issues.findById(issue.getId()).orElseThrow().getCommentsCount())
                .isZero();
        upsert(repository, 2, now.plusSeconds(3));
        var positive = issues.findById(issue.getId()).orElseThrow();
        assertThat(positive.getCommentsCount()).isEqualTo(2);
        var missing = capture(positive, workspace.getId(), providerType);
        assertThat(missing.completeness()).containsEntry(IssueContentSource.COMMENTS, SourceCompleteness.PARTIAL);
        assertThat(missing.captureLimitations())
                .containsEntry(IssueContentSource.COMMENTS, List.of("COMMENTS_NOT_READ_WHOLE"));

        for (long nativeId : List.of(1L, 2L)) {
            var comment = new IssueComment();
            comment.setProvider(provider);
            comment.setNativeId(nativeId);
            comment.setIssue(positive);
            comment.setBody("A captured comment");
            comment.setHtmlUrl("https://example.com/repo/issues/1#comment-" + nativeId);
            comment.setAuthorAssociation(AuthorAssociation.NONE);
            comment.setCreatedAt(now);
            comment.setUpdatedAt(now);
            comments.saveAndFlush(comment);
        }
        var whole = capture(positive, workspace.getId(), providerType);
        assertThat(whole.completeness()).containsEntry(IssueContentSource.COMMENTS, SourceCompleteness.COMPLETE);
        assertThat(whole.contentStates()).containsEntry(IssueContentSource.COMMENTS, SourceContentState.NON_EMPTY);
        assertThat(whole.captureLimitations()).isEmpty();
    }

    private void connect(Workspace workspace, IdentityProviderType providerType, String serverUrl) {
        IntegrationKind kind = IntegrationKind.valueOf(providerType.name());
        ConnectionConfig config = kind == IntegrationKind.GITHUB
                ? new ConnectionConfig.GitHubAppConfig(1L, "example", serverUrl, Set.of())
                : new ConnectionConfig.GitLabConfig(serverUrl, 42L, null, Set.of(), null);
        var connection = new Connection(workspace, kind, serverUrl, config);
        ReflectionTestUtils.setField(connection, "state", IntegrationState.ACTIVE);
        connections.saveAndFlush(connection);
    }

    private void upsert(Repository repository, @Nullable Integer count, Instant updatedAt) {
        issues.upsertCore(
                1L,
                Objects.requireNonNull(repository.getProvider().getId()),
                1,
                "Work",
                null,
                "OPEN",
                null,
                "https://example.com/repo/issues/1",
                null,
                null,
                count,
                updatedAt,
                updatedAt,
                updatedAt,
                null,
                repository.getId(),
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private CaptureContribution capture(Issue issue, long workspaceId, IdentityProviderType providerType) {
        var repository = Objects.requireNonNull(issue.getRepository());
        var metadata =
                mapper.createObjectNode().put("issue_id", issue.getId()).put("repository_id", repository.getId());
        var fixture = ScmCaptureFixtures.context(metadata, workspaceId, true);
        var pinned = ItemFixtures.snapshot(fixture.pinned().item(), Map.of())
                .version(revisions.of(ScmEventPayload.IssueData.from(issue)))
                .integration(new IntegrationRef(IntegrationKind.valueOf(providerType.name()), workspaceId, null))
                .build();
        return source.capture(
                new ScmCaptureContext(
                        pinned, fixture.request(), fixture.work(), fixture.preparation(), fixture.capturedAt()),
                Set.of(IssueContentSource.COMMENTS));
    }
}
