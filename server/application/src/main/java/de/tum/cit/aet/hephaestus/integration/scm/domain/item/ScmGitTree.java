package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.tum.cit.aet.hephaestus.core.CacheLoads;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ScmGitTree {
    static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private final GitRepositoryManager git;
    // Git objects are immutable. Callers still authorize repository access before each lookup.
    private final AsyncCache<PinnedTree, Inventory> inventories = Caffeine.newBuilder()
            .maximumWeight(200_000)
            .weigher((PinnedTree key, Inventory value) ->
                    Math.max(1, value.entries().size()))
            .expireAfterAccess(Duration.ofHours(2))
            .buildAsync();

    private record PinnedTree(RepositoryKey repository, String commit) {}

    public static final class Inventory {
        private final List<ScmFileVersions.Entry> entries;
        private final Map<String, ScmFileVersions.Entry> paths;
        private final Map<List<String>, List<ScmFileVersions.Entry>> contexts = new ConcurrentHashMap<>();
        private final Map<Composition, ItemVersion> composed = new ConcurrentHashMap<>();

        public Inventory(List<ScmFileVersions.Entry> entries) {
            this.entries = entries.stream()
                    .sorted(Comparator.comparing(ScmFileVersions.Entry::path))
                    .toList();
            this.paths =
                    this.entries.stream().collect(Collectors.toUnmodifiableMap(ScmFileVersions.Entry::path, e -> e));
        }

        public List<ScmFileVersions.Entry> entries() {
            return entries;
        }

        public ScmFileVersions.Entry assigned(String path) {
            var entry = paths.get(path);
            if (entry == null)
                throw new ItemCaptureException(
                        ItemCaptureCode.ITEM_REMOVED, "Assigned file is unavailable at the pinned commit");
            return entry;
        }

        private record Composition(String path, List<String> context, String commit) {}

        private List<ScmFileVersions.Entry> context(List<String> globs) {
            return contexts.computeIfAbsent(globs, selected -> ScmFileVersions.contextEntries("", selected, entries));
        }

        public List<String> contextPaths(String assignedPath, List<String> globs) {
            var selected = globs.stream().distinct().sorted().toList();
            return context(selected).stream()
                    .map(ScmFileVersions.Entry::path)
                    .filter(path -> !path.equals(assignedPath))
                    .toList();
        }

        public ItemVersion version(ScmFileVersions versions, String path, List<String> globs, String commit) {
            var selected = globs.stream().distinct().sorted().toList();
            return composed.computeIfAbsent(new Composition(path, selected, commit), key -> {
                var entry = assigned(path);
                return versions.fromContext(
                        entry,
                        selected,
                        context(selected).stream()
                                .filter(e -> !e.path().equals(path))
                                .toList(),
                        commit);
            });
        }
    }

    public GitRepositoryManager.GitTreeSnapshot snapshot(RepositoryKey key, String sha, Set<String> paths) {
        return git.readScanSnapshot(key, sha, paths);
    }

    public String resolve(RepositoryKey key, String ref) {
        try {
            return Objects.requireNonNull(git.read(key, repository -> {
                var id = repository.resolve(ref + "^{commit}");
                if (id == null) throw unavailable("Repository version is unavailable");
                return id.name();
            }));
        } catch (GitRepositoryManager.GitOperationException e) {
            throw new ItemCaptureException(ItemCaptureCode.VERSION_UNAVAILABLE, "Repository version is unavailable", e);
        }
    }

    public List<ScmFileVersions.Entry> inventory(RepositoryKey key, String commit) {
        return index(key, commit).entries();
    }

    public Inventory index(RepositoryKey key, String commit) {
        if (!ObjectId.isId(commit)) throw unavailable("Repository version is not a full Git identity");
        return CacheLoads.get(
                inventories, new PinnedTree(key, commit), pinned -> new Inventory(readInventory(key, commit)));
    }

    private List<ScmFileVersions.Entry> readInventory(RepositoryKey key, String commit) {
        if (!ObjectId.isId(commit)) throw unavailable("Repository version is not a full Git identity");
        try {
            return Objects.requireNonNull(git.read(key, repository -> {
                List<ScmFileVersions.Entry> files = new ArrayList<>();
                try (RevWalk walk = new RevWalk(repository);
                        TreeWalk tree = new TreeWalk(repository)) {
                    tree.addTree(walk.parseCommit(ObjectId.fromString(commit)).getTree());
                    tree.setRecursive(true);
                    while (tree.next()) {
                        FileMode mode = tree.getFileMode(0);
                        if (mode.equals(FileMode.REGULAR_FILE) || mode.equals(FileMode.EXECUTABLE_FILE)) {
                            var object = repository.open(tree.getObjectId(0), Constants.OBJ_BLOB);
                            long size = object.getSize();
                            boolean binary = false;
                            if (size <= MAX_FILE_BYTES) {
                                try (var content = object.openStream()) {
                                    binary = RawText.isBinary(content.readNBytes(8000));
                                }
                            }
                            files.add(new ScmFileVersions.Entry(
                                    tree.getPathString(),
                                    Integer.toOctalString(mode.getBits()),
                                    tree.getObjectId(0).name(),
                                    size,
                                    binary));
                        }
                    }
                }
                files.sort(Comparator.comparing(ScmFileVersions.Entry::path));
                return List.copyOf(files);
            }));
        } catch (GitRepositoryManager.GitOperationException e) {
            throw new ItemCaptureException(
                    ItemCaptureCode.VERSION_UNAVAILABLE, "Pinned repository tree is unavailable", e);
        }
    }

    public byte[] content(RepositoryKey key, ScmFileVersions.Entry entry) {
        try {
            return Objects.requireNonNull(git.read(key, repository -> {
                var object = repository.open(ObjectId.fromString(entry.objectId()), Constants.OBJ_BLOB);
                if (object.getSize() > MAX_FILE_BYTES)
                    throw new ItemCaptureException(
                            ItemCaptureCode.CAPTURE_FAILED, "Assigned file exceeds the capture limit");
                byte[] bytes = object.getBytes(MAX_FILE_BYTES);
                if (RawText.isBinary(bytes))
                    throw new ItemCaptureException(ItemCaptureCode.CAPTURE_FAILED, "Assigned file is binary");
                return bytes;
            }));
        } catch (GitRepositoryManager.GitOperationException e) {
            throw new ItemCaptureException(
                    ItemCaptureCode.VERSION_UNAVAILABLE, "Pinned file content is unavailable", e);
        }
    }

    private static ItemCaptureException unavailable(String message) {
        return new ItemCaptureException(ItemCaptureCode.VERSION_UNAVAILABLE, message);
    }
}
