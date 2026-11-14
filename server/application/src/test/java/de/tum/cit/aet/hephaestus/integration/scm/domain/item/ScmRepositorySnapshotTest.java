package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ScmTokenSource;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class ScmRepositorySnapshotTest extends BaseUnitTest {
    @ParameterizedTest
    @ValueSource(strings = {"main", "release/1.0"})
    void shouldExposeTheActualDefaultBranchAlongsideThePinnedCommit(String branch) {
        var items = mock(ScmItemAccess.class);
        var rows = mock(RepositoryRepository.class);
        var access = mock(ScmRepositoryAccess.class);
        var tree = mock(ScmGitTree.class);
        var ref = new ItemRef(1, 2, ScmItemEvents.REPOSITORY, 3L, null, null);
        var repository = new Repository();
        repository.setId(3L);
        repository.setDefaultBranch(branch);
        repository.setNameWithOwner("owner/repository");
        var key = new RepositoryKey(1, 3);
        when(items.require(ref)).thenReturn(ref);
        when(rows.findById(3L)).thenReturn(Optional.of(repository));
        when(access.authorize(1, repository)).thenReturn(new IntegrationRef(IntegrationKind.GITHUB, 1, null));
        var remote = new ScmRepositoryAccess.Remote(
                key, "https://scm.example/owner/repository.git", mock(ScmTokenSource.class));
        when(access.remote(ref)).thenReturn(remote);
        when(access.remote(1, repository)).thenReturn(remote);
        when(tree.resolve(key, "refs/heads/" + branch)).thenReturn("a".repeat(40));
        when(tree.inventory(key, "a".repeat(40))).thenReturn(List.of());
        var service = new ScmItemService(
                items,
                access,
                rows,
                mock(ScmWorkLookup.class),
                mock(PullRequestReviewRepository.class),
                mock(IssueEvidenceVersion.class),
                tree,
                mock(ScmFileVersions.class),
                new ObjectMapper(),
                mock(ScmWorkCapture.class),
                new ScmItemTransactions(),
                Clock.systemUTC());
        var snapshot = service.pin(ref, null);
        assertThat(snapshot.state()).containsEntry("defaultBranch", branch);
        assertThat(snapshot.version().value()).isEqualTo("a".repeat(40));
    }
}
