package de.tum.cit.aet.hephaestus.integration.scm.github.subissue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderFixtures;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssue;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssueConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** Exercises the native page processor against committed provider-qualified repository identities. */
class GitHubSubIssueReconciliationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private GitHubSubIssueSyncService service;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private IssueRepository issues;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void shouldSkipAnUnmirroredGitHubParentWhenOnlyGitLabHasItsPathAndNumber() {
        var github = IdentityProviderFixtures.gitHub(providers);
        var gitlab = providers.save(new IdentityProvider(
                IdentityProviderType.GITLAB, "https://" + UUID.randomUUID() + ".gitlab.example.test"));
        long nativeId =
                1_000_000_000L + Integer.toUnsignedLong(UUID.randomUUID().hashCode()) * 10;
        var current = repository(github, nativeId + 1, "identity-" + UUID.randomUUID() + "/children");
        var parentPath = "identity-" + UUID.randomUUID() + "/parent";
        var foreignRepository = repository(gitlab, nativeId + 2, parentPath);
        var foreignParent = issue(foreignRepository, nativeId + 3, 19);
        var child = issue(current, nativeId + 4, 20);
        var allowedParent = issue(current, nativeId + 5, 21);
        var allowedChild = issue(current, nativeId + 6, 22);
        assertThat(parentId(child)).isEmpty();
        assertThat(parentId(allowedChild)).isEmpty();
        assertThat(repositories.findByNameWithOwnerAndProviderId(parentPath, Objects.requireNonNull(github.getId())))
                .isEmpty();

        var childNode = node(child, current.getNameWithOwner());
        childNode.setParent(node(foreignParent, parentPath));
        var allowedChildNode = node(allowedChild, current.getNameWithOwner());
        allowedChildNode.setParent(node(allowedParent, current.getNameWithOwner()));
        var page = new GHIssueConnection();
        page.setNodes(List.of(childNode, allowedChildNode));

        int linked =
                Objects.requireNonNull(transactions.execute(status -> service.processIssueNodes(page, current, 1L)));

        assertSoftly(softly -> {
            softly.assertThat(parentId(child)).isEmpty();
            softly.assertThat(parentId(allowedChild)).contains(allowedParent.getId());
            softly.assertThat(parentId(foreignParent)).isEmpty();
            softly.assertThat(linked).isEqualTo(1);
        });

        child.setParentIssue(foreignParent);
        issues.save(child);
        assertThat(parentId(child)).contains(foreignParent.getId());

        int repaired =
                Objects.requireNonNull(transactions.execute(status -> service.processIssueNodes(page, current, 1L)));

        assertThat(parentId(child)).isEmpty();
        assertThat(parentId(allowedChild)).contains(allowedParent.getId());
        assertThat(repaired).isZero();

        child.setParentIssue(allowedParent);
        issues.save(child);
        int unresolved =
                Objects.requireNonNull(transactions.execute(status -> service.processIssueNodes(page, current, 1L)));

        assertThat(parentId(child)).contains(allowedParent.getId());
        assertThat(unresolved).isZero();

        child.setParentIssue(foreignParent);
        issues.save(child);
        var unreadableParent = new GHIssue();
        unreadableParent.setFullDatabaseId(BigInteger.valueOf(foreignParent.getNativeId()));
        unreadableParent.setNumber(foreignParent.getNumber());
        childNode.setParent(unreadableParent);
        int unreadable =
                Objects.requireNonNull(transactions.execute(status -> service.processIssueNodes(page, current, 1L)));

        assertThat(parentId(child)).contains(foreignParent.getId());
        assertThat(unreadable).isZero();

        childNode.setParent(node(foreignParent, parentPath));
        var mirroredRepository = repository(github, foreignRepository.getNativeId(), parentPath);
        var mirroredParent = issue(mirroredRepository, foreignParent.getNativeId(), foreignParent.getNumber());

        int recovered =
                Objects.requireNonNull(transactions.execute(status -> service.processIssueNodes(page, current, 1L)));

        assertThat(parentId(child)).contains(mirroredParent.getId());
        assertThat(parentId(allowedChild)).contains(allowedParent.getId());
        assertThat(parentId(foreignParent)).isEmpty();
        assertThat(recovered).isEqualTo(1);
    }

    private Optional<Long> parentId(Issue issue) {
        return Objects.requireNonNull(transactions.execute(status -> {
            var stored = issues.findById(issue.getId()).orElseThrow();
            return Optional.ofNullable(stored.getParentIssue()).map(Issue::getId);
        }));
    }

    private Repository repository(IdentityProvider provider, long nativeId, String path) {
        var created = new Repository();
        created.setProvider(provider);
        created.setNativeId(nativeId);
        created.setName(path.substring(path.lastIndexOf('/') + 1));
        created.setNameWithOwner(path);
        created.setHtmlUrl(provider.getServerUrl() + "/" + path);
        created.setPushedAt(Instant.now());
        created.setVisibility(Repository.Visibility.PRIVATE);
        return repositories.save(created);
    }

    private Issue issue(Repository repository, long nativeId, int number) {
        var created = new Issue();
        created.setProvider(repository.getProvider());
        created.setNativeId(nativeId);
        created.setRepository(repository);
        created.setNumber(number);
        created.setTitle("Issue " + number);
        created.setState(Issue.State.OPEN);
        return issues.save(created);
    }

    private static GHIssue node(Issue issue, String repositoryPath) {
        var node = new GHIssue();
        node.setFullDatabaseId(BigInteger.valueOf(issue.getNativeId()));
        node.setNumber(issue.getNumber());
        var repository = new GHRepository();
        repository.setNameWithOwner(repositoryPath);
        node.setRepository(repository);
        return node;
    }
}
