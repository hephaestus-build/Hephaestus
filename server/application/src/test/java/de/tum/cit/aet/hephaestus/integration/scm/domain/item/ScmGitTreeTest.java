package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.GitTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.PinnedParks;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class ScmGitTreeTest extends BaseUnitTest {
    @TempDir
    Path root;

    @Test
    void shouldReadOnePinnedTreeForListingEveryCompositionAndEveryContextGrant() throws Exception {
        try (var repository = Git.init().setDirectory(root.toFile()).call()) {
            GitTestFixtures.disableSigning(repository.getRepository());
            Files.writeString(root.resolve("a.java"), "class A {}\n");
            Files.writeString(root.resolve("b.java"), "class B {}\n");
            Files.writeString(root.resolve("README.md"), "The project\n");
            Files.writeString(root.resolve("large.txt"), "a".repeat(ScmGitTree.MAX_FILE_BYTES + 1));
            Files.writeString(root.resolve("limit.txt"), "a".repeat(ScmGitTree.MAX_FILE_BYTES));
            Files.write(root.resolve("binary.bin"), new byte[] {0, 1, 2});
            repository.add().addFilepattern(".").call();
            String commit = repository
                    .commit()
                    .setSign(false)
                    .setMessage("fixture")
                    .setAuthor("Test", "test@example.org")
                    .setCommitter("Test", "test@example.org")
                    .call()
                    .name();
            var key = new RepositoryKey(1, 2);
            var manager = mock(GitRepositoryManager.class);
            when(manager.read(eq(key), any())).thenAnswer(call -> {
                GitRepositoryManager.MirrorRead<?> operation = call.getArgument(1);
                return operation.apply(repository.getRepository());
            });
            var tree = new ScmGitTree(manager);
            var versions = new ScmFileVersions(new JsonMapper());
            var listed = tree.inventory(key, commit);
            assertThat(listed).hasSize(6);
            var indexed = tree.index(key, commit);
            assertThat(indexed.assigned("large.txt").sizeBytes()).isEqualTo(ScmGitTree.MAX_FILE_BYTES + 1L);
            assertThat(indexed.assigned("limit.txt").sizeBytes()).isEqualTo(ScmGitTree.MAX_FILE_BYTES);
            assertThat(indexed.assigned("limit.txt").binary()).isFalse();
            assertThat(indexed.assigned("binary.bin").binary()).isTrue();
            for (var file : listed) {
                var index = tree.index(key, commit);
                assertThat(index.assigned(file.path())).isEqualTo(file);
                for (var context : List.of(List.of("**/*"), List.of("*.java"))) {
                    assertThat(index.version(versions, file.path(), context, commit))
                            .isEqualTo(versions.version(
                                    file.path(), file.objectId(), file.mode(), context, listed, commit));
                    assertThat(tree.index(key, commit).contextPaths(file.path(), context))
                            .doesNotContain(file.path());
                }
            }
            verify(manager, times(1)).read(eq(key), any());
        }
    }

    /** Captures run on virtual threads; no park while the tree is read may pin one to its carrier. */
    @Test
    void shouldReadAPinnedTreeWithoutPinningTheVirtualThreadThatAsks() throws Exception {
        String commit = "a".repeat(40);
        var key = new RepositoryKey(1, 2);
        var manager = mock(GitRepositoryManager.class);
        when(manager.read(eq(key), any())).thenAnswer(read -> {
            Thread.sleep(50);
            return List.of();
        });
        var tree = new ScmGitTree(manager);

        assertThat(PinnedParks.during(() -> tree.index(key, commit)))
                .as("pinned parks while the tree was read")
                .isEmpty();
    }
}
