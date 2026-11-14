package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ScmTokenSource;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Objects;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ScmFileVersionComposerTest extends BaseUnitTest {
    @Test
    void shouldFreezeOnlyTheDeclaredContextAtThePinnedContainerCommit() {
        var items = mock(ScmItemAccess.class);
        var access = mock(ScmRepositoryAccess.class);
        var tree = mock(ScmGitTree.class);
        var file = new ItemRef(1, 2, ScmItemEvents.FILE, null, 3L, "src/a.ts");
        var key = new RepositoryKey(1, 4);
        var pinned = new ItemVersion("a".repeat(40), null);
        var remote =
                new ScmRepositoryAccess.Remote(key, "https://scm.example/owner/repo.git", mock(ScmTokenSource.class));
        when(access.remote(file)).thenReturn(remote);
        when(access.sync(remote)).thenReturn(key);
        var self = new ScmFileVersions.Entry("src/a.ts", "100644", "b".repeat(40));
        var context = new ScmFileVersions.Entry("src/b.ts", "100644", "c".repeat(40));
        var unrelated = new ScmFileVersions.Entry("README.md", "100644", "d".repeat(40));
        when(tree.index(key, pinned.value())).thenReturn(new ScmGitTree.Inventory(List.of(self, context, unrelated)));
        var composer = new ScmFileVersionComposer(
                items, access, tree, new ScmFileVersions(new ObjectMapper()), new ScmItemTransactions());
        assertThat(composer.kinds()).containsExactly(ScmItemEvents.FILE);
        var original = Objects.requireNonNull(
                composer.compose(List.of(file), pinned, List.of("src/**")).get(file.itemId()));
        assertThat(original.baseVersion()).isEqualTo(pinned.value());
        assertThat(composer.contextPaths(file, original, List.of("src/**"))).containsExactly("src/b.ts");
        assertThatThrownBy(() -> composer.contextPaths(file, original, List.of("README.md")))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ItemCaptureCode.VERSION_UNAVAILABLE));
        for (var invalid :
                List.of(new ItemVersion(original.value(), null), new ItemVersion(original.value(), "main"))) {
            assertThatThrownBy(() -> composer.contextPaths(file, invalid, List.of("src/**")))
                    .isInstanceOfSatisfying(
                            ItemCaptureException.class,
                            failure -> assertThat(failure.code()).isEqualTo(ItemCaptureCode.VERSION_UNAVAILABLE));
        }
        when(tree.index(key, pinned.value()))
                .thenReturn(new ScmGitTree.Inventory(List.of(
                        self, context, new ScmFileVersions.Entry(unrelated.path(), unrelated.mode(), "e".repeat(40)))));
        assertThat(Objects.requireNonNull(composer.compose(List.of(file), pinned, List.of("src/**"))
                        .get(file.itemId())))
                .isEqualTo(original);
        assertThat(composer.contextPaths(file, original, List.of("src/**"))).containsExactly("src/b.ts");
        when(tree.index(key, pinned.value()))
                .thenReturn(new ScmGitTree.Inventory(List.of(
                        self, new ScmFileVersions.Entry(context.path(), context.mode(), "f".repeat(40)), unrelated)));
        assertThat(Objects.requireNonNull(composer.compose(List.of(file), pinned, List.of("src/**"))
                        .get(file.itemId())))
                .isNotEqualTo(original);
        assertThatThrownBy(() -> composer.contextPaths(file, original, List.of("src/**")))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ItemCaptureCode.VERSION_UNAVAILABLE));
    }

    @Test
    void shouldComposeLargeSelectionsFromOnePreparedRepositoryAndIndex() {
        var items = mock(ScmItemAccess.class);
        var access = mock(ScmRepositoryAccess.class);
        var tree = mock(ScmGitTree.class);
        var selected = LongStream.range(10, 2011)
                .mapToObj(id -> new ItemRef(1, id, ScmItemEvents.FILE, null, 3L, "src/" + id + ".ts"))
                .toList();
        var first = selected.getFirst();
        var key = new RepositoryKey(1, 4);
        var pinned = new ItemVersion("a".repeat(40), null);
        var remote =
                new ScmRepositoryAccess.Remote(key, "https://scm.example/owner/repo.git", mock(ScmTokenSource.class));
        when(access.remote(first)).thenReturn(remote);
        when(access.sync(remote)).thenReturn(key);
        when(tree.index(key, pinned.value()))
                .thenReturn(new ScmGitTree.Inventory(selected.stream()
                        .map(file -> new ScmFileVersions.Entry(
                                Objects.requireNonNull(file.path()), "100644", "b".repeat(40)))
                        .toList()));
        var composer = new ScmFileVersionComposer(
                items, access, tree, new ScmFileVersions(new ObjectMapper()), new ScmItemTransactions());
        assertThat(composer.compose(selected, pinned, List.of()).keySet())
                .containsExactlyInAnyOrderElementsOf(
                        selected.stream().map(ItemRef::itemId).toList());
        verify(items).requireAll(1, selected.subList(0, 1000));
        verify(items).requireAll(1, selected.subList(1000, 2000));
        verify(items).requireAll(1, selected.subList(2000, 2001));
        verify(access, times(1)).sync(remote);
        verify(tree, times(1)).index(key, pinned.value());
    }
}
