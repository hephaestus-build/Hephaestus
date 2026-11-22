package de.tum.cit.aet.hephaestus.integration.scm.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.MirroredThreadStateQuery;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The thread state that a feedback copy's thread ref names, read from the pull and merge request mirror: a GitHub
 * review thread by its node id, a GitLab discussion by its global id. Only the threads of the workspace's own items
 * are read.
 */
class ScmMirroredThreadStateQueryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MirroredThreadStateQuery threads;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private WorkspaceRepository workspaces;

    @Test
    void shouldReadGitHubThreadsByTheirNodeIdWhenTheMirrorHoldsThem() {
        var work = work(IdentityProviderType.GITHUB);
        String open = "PRRT_" + UUID.randomUUID();
        String resolved = "PRRT_" + UUID.randomUUID();
        String unsynced = "PRRT_" + UUID.randomUUID();
        thread(work, open, "UNRESOLVED");
        thread(work, resolved, "RESOLVED");
        // The review-comment webhook stores a thread without its node id until a thread event or a sync names it.
        thread(work, null, "RESOLVED");

        assertThat(threads.resolved(work.workspaceId(), List.of(work.itemId()), List.of(open, resolved, unsynced)))
                .isEqualTo(Map.of(open, false, resolved, true));
    }

    @Test
    void shouldReadAGitLabDiscussionByItsGlobalId() {
        var work = work(IdentityProviderType.GITLAB);
        String discussion =
                "gid://gitlab/Discussion/" + UUID.randomUUID().toString().replace("-", "");
        thread(work, discussion, "RESOLVED");

        assertThat(threads.resolved(work.workspaceId(), List.of(work.itemId()), List.of(discussion)))
                .isEqualTo(Map.of(discussion, true));
    }

    @Test
    void shouldNotReadAThreadOfAnotherWorkspace() {
        var mine = work(IdentityProviderType.GITHUB);
        var theirs = work(IdentityProviderType.GITHUB);
        String theirThread = "PRRT_" + UUID.randomUUID();
        thread(theirs, theirThread, "RESOLVED");

        assertThat(threads.resolved(mine.workspaceId(), List.of(mine.itemId(), theirs.itemId()), List.of(theirThread)))
                .isEmpty();
        assertThat(threads.resolved(theirs.workspaceId(), List.of(theirs.itemId()), List.of(theirThread)))
                .isEqualTo(Map.of(theirThread, true));
    }

    /** A pull or merge request in its own repository, indexed as an item of a new workspace. */
    private record Work(long workspaceId, long itemId, long providerId, long pullRequestId) {}

    private Work work(IdentityProviderType type) {
        String unique = UUID.randomUUID().toString();
        var workspace = new Workspace();
        workspace.setWorkspaceSlug("threads-" + unique);
        workspace.setDisplayName("Thread state");
        workspace.setAccountLogin("threads");
        workspace.setAccountType(AccountType.ORG);
        long workspaceId =
                Objects.requireNonNull(workspaces.saveAndFlush(workspace).getId());
        long providerId = Objects.requireNonNull(providers
                .saveAndFlush(new IdentityProvider(type, "https://" + unique + ".example.com"))
                .getId());
        long repositoryId =
                Objects.requireNonNull(jdbc.queryForObject("""
                INSERT INTO repository(native_id,provider_id,name,name_with_owner,is_private,is_archived,is_disabled,
                  html_url,pushed_at,visibility)
                VALUES (1,?,'repo',?,false,false,false,'https://example.com/repo',now(),'PUBLIC') RETURNING id
                """, Long.class, providerId, "threads/" + unique));
        long pullRequestId = Objects.requireNonNull(jdbc.queryForObject("""
                INSERT INTO issue(issue_type,native_id,provider_id,repository_id,number,created_at,state,comments_count,
                  is_locked,is_merged,title,is_draft,additions,deletions,changed_files,commits)
                VALUES ('PULL_REQUEST',1,?,?,1,now(),'OPEN',0,false,false,'Work',false,0,0,0,0) RETURNING id
                """, Long.class, providerId, repositoryId));
        long itemId = Objects.requireNonNull(jdbc.queryForObject(
                "INSERT INTO item(workspace_id,kind,source_id,created_at) VALUES (?,'scm.pull_request',?,now())"
                        + " RETURNING id",
                Long.class,
                workspaceId,
                pullRequestId));
        return new Work(workspaceId, itemId, providerId, pullRequestId);
    }

    private void thread(Work work, @Nullable String nodeId, String state) {
        jdbc.update(
                "INSERT INTO pull_request_review_thread(native_id,provider_id,pull_request_id,node_id,state)"
                        + " VALUES (?,?,?,?,?)",
                ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE),
                work.providerId(),
                work.pullRequestId(),
                nodeId,
                state);
    }
}
