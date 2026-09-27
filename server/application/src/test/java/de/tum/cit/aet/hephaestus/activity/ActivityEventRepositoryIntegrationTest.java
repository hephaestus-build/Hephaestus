package de.tum.cit.aet.hephaestus.activity;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.Commit;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for {@link ActivityEventRepository#backfillCommitActors(Long)}.
 *
 * <p>Gap #6 reconciliation: commits ingested before GitLab authors are resolved
 * land COMMIT_CREATED activity events with {@code actor_id = NULL}. Once
 * {@code git_commit.author_id} is backfilled via email match, this native UPDATE
 * attributes the events to the contributor.
 */
@Transactional
class ActivityEventRepositoryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ActivityEventRepository activityEventRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private CommitRepository commitRepository;

    private Workspace workspace;
    private IdentityProvider gitProvider;
    private User author;
    private Repository targetRepository;
    private Repository otherRepository;

    @BeforeEach
    void setUp() {
        workspace = new Workspace();
        workspace.setWorkspaceSlug("backfill-workspace");
        workspace.setDisplayName("Backfill Workspace");
        workspace.setAccountLogin("backfill-org");
        workspace.setAccountType(AccountType.ORG);
        workspace = workspaceRepository.save(workspace);

        gitProvider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, "https://gitlab.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.com")));

        author = new User();
        author.setNativeId(900L);
        author.setLogin("alice");
        author.setName("Alice");
        author.setAvatarUrl("https://example.com/alice.png");
        author.setHtmlUrl("https://gitlab.com/alice");
        author.setType(User.Type.USER);
        author.setCreatedAt(Instant.now());
        author.setUpdatedAt(Instant.now());
        author.setProvider(gitProvider);
        author = userRepository.save(author);

        targetRepository = persistRepository(5001L, "backfill-org/target");
        otherRepository = persistRepository(5002L, "backfill-org/sibling");
    }

    private Repository persistRepository(long nativeId, String nameWithOwner) {
        Repository repo = new Repository();
        repo.setNativeId(nativeId);
        repo.setName(nameWithOwner.substring(nameWithOwner.indexOf('/') + 1));
        repo.setNameWithOwner(nameWithOwner);
        repo.setHtmlUrl("https://gitlab.com/" + nameWithOwner);
        repo.setVisibility(Repository.Visibility.PRIVATE);
        repo.setDefaultBranch("main");
        repo.setPushedAt(Instant.now());
        repo.setCreatedAt(Instant.now());
        repo.setUpdatedAt(Instant.now());
        repo.setProvider(gitProvider);
        return repositoryRepository.save(repo);
    }

    private Commit persistCommit(String sha, Repository repo, @Nullable User commitAuthor) {
        Commit commit = new Commit();
        commit.setSha(sha);
        commit.setMessage("initial commit");
        commit.setAuthoredAt(Instant.parse("2024-06-01T10:00:00Z"));
        commit.setCommittedAt(Instant.parse("2024-06-01T10:00:00Z"));
        commit.setRepository(repo);
        commit.setAuthor(commitAuthor);
        commit.setAuthorEmail("alice@example.com");
        return commitRepository.save(commit);
    }

    private ActivityEvent persistCommitCreatedEvent(Commit commit, @Nullable User actor) {
        Instant occurredAt = commit.getAuthoredAt();
        ActivityEvent event = ActivityEvent.builder()
                .id(UUID.randomUUID())
                .eventKey(ActivityEventType.COMMIT_CREATED.getValue() + ":"
                        + commit.getId()
                        + ":"
                        + occurredAt.toEpochMilli()
                        + ":"
                        + UUID.randomUUID())
                .eventType(ActivityEventType.COMMIT_CREATED)
                .occurredAt(occurredAt)
                .actor(actor)
                .workspace(workspace)
                .repository(commit.getRepository())
                .targetType(ActivityTargetType.COMMIT.getValue())
                .targetId(commit.getId())
                .ingestedAt(Instant.now())
                .build();
        return activityEventRepository.save(event);
    }

    @Test
    void backfillsOrphanCommitEvents() {
        Commit commit = persistCommit("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", targetRepository, null);
        ActivityEvent persisted = persistCommitCreatedEvent(commit, null);

        // Simulate the post-enrichment state: author_id resolved on git_commit.
        commit.setAuthor(author);
        commitRepository.save(commit);

        int updated = activityEventRepository.backfillCommitActors(targetRepository.getId());

        assertThat(updated).isEqualTo(1);
        ActivityEvent refreshed =
                activityEventRepository.findById(persisted.getId()).orElseThrow();
        assertThat(refreshed.getActor()).isNotNull();
        assertThat(refreshed.getActor().getId()).isEqualTo(author.getId());
    }

    @Test
    @DisplayName("does not touch events whose git_commit still has a null author_id")
    void skipsCommitsWithUnresolvedAuthor() {
        Commit commit = persistCommit("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", targetRepository, null);
        persistCommitCreatedEvent(commit, null);

        int updated = activityEventRepository.backfillCommitActors(targetRepository.getId());

        assertThat(updated).isZero();
    }

    @Test
    void skipsEventsAlreadyHavingActor() {
        Commit commit = persistCommit("cccccccccccccccccccccccccccccccccccccccc", targetRepository, author);
        ActivityEvent persisted = persistCommitCreatedEvent(commit, author);

        int updated = activityEventRepository.backfillCommitActors(targetRepository.getId());

        assertThat(updated).isZero();
        ActivityEvent refreshed =
                activityEventRepository.findById(persisted.getId()).orElseThrow();
        assertThat(refreshed.getActor()).isNotNull();
        assertThat(refreshed.getActor().getId()).isEqualTo(author.getId());
    }

    @Test
    void scopesBackfillToRequestedRepository() {
        Commit targetCommit = persistCommit("dddddddddddddddddddddddddddddddddddddddd", targetRepository, null);
        Commit otherCommit = persistCommit("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", otherRepository, null);
        ActivityEvent targetEvent = persistCommitCreatedEvent(targetCommit, null);
        ActivityEvent otherEvent = persistCommitCreatedEvent(otherCommit, null);

        targetCommit.setAuthor(author);
        otherCommit.setAuthor(author);
        commitRepository.save(targetCommit);
        commitRepository.save(otherCommit);

        int updated = activityEventRepository.backfillCommitActors(targetRepository.getId());

        assertThat(updated).isEqualTo(1);
        ActivityEvent refreshedTarget =
                activityEventRepository.findById(targetEvent.getId()).orElseThrow();
        ActivityEvent refreshedOther =
                activityEventRepository.findById(otherEvent.getId()).orElseThrow();
        assertThat(refreshedTarget.getActor()).isNotNull();
        assertThat(refreshedTarget.getActor().getId()).isEqualTo(author.getId());
        assertThat(refreshedOther.getActor()).isNull();
    }

    @Test
    void ignoresNonCommitEvents() {
        Instant occurredAt = Instant.parse("2024-06-01T10:00:00Z");
        ActivityEvent prEvent = ActivityEvent.builder()
                .id(UUID.randomUUID())
                .eventKey(ActivityEventType.PULL_REQUEST_OPENED.getValue() + ":42:" + occurredAt.toEpochMilli())
                .eventType(ActivityEventType.PULL_REQUEST_OPENED)
                .occurredAt(occurredAt)
                .actor(null)
                .workspace(workspace)
                .repository(targetRepository)
                .targetType(ActivityTargetType.PULL_REQUEST.getValue())
                .targetId(42L)
                .ingestedAt(Instant.now())
                .build();
        activityEventRepository.save(prEvent);

        int updated = activityEventRepository.backfillCommitActors(targetRepository.getId());

        assertThat(updated).isZero();
        ActivityEvent refreshed =
                activityEventRepository.findById(prEvent.getId()).orElseThrow();
        assertThat(refreshed.getActor()).isNull();
    }
}
