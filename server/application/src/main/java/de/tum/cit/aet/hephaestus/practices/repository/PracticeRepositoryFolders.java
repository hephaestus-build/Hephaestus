package de.tum.cit.aet.hephaestus.practices.repository;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.MonitoredRepositoryQuery;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryCheckoutException;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryCheckouts;
import de.tum.cit.aet.hephaestus.practices.PracticeFolderInvalidException;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeFileProblemDTO;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Reads practice folders from the head of the practice repository's default branch. The checkout holds no credential,
 * and the reader follows no symbolic link, so a folder can only name files inside it.
 */
@Slf4j
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class PracticeRepositoryFolders {
    /** More than a folder may hold, so a reader stops before it loads a large tree into memory. */
    private static final int MAX_FILES = 64;

    /** The limits that {@code PracticeFiles} applies, checked before a file is read into memory. */
    private static final long MAX_FILE_BYTES = 128 * 1024;

    private static final long MAX_FOLDER_BYTES = 256 * 1024;

    /** The most practice folders that a sync reads, so it stops before it loads a large tree into memory. */
    static final int MAX_FOLDERS = 200;

    private final PracticeRepositorySettingRepository settings;
    private final MonitoredRepositoryQuery monitored;
    private final RepositoryCheckouts checkouts;

    /**
     * The setting that a read was made under. A change of the setting raises its revision. A setting unset and set
     * again is a new row whose revision starts at 0 again, so its update time tells it apart from the one read.
     */
    public record ReadUnder(
            long repositoryItemId,
            String directory,
            long revision,
            @Nullable Instant settingUpdatedAt) {
        static ReadUnder of(PracticeRepositorySetting setting) {
            return new ReadUnder(
                    setting.getRepositoryItemId(),
                    setting.getDirectory(),
                    setting.getRevision(),
                    setting.getUpdatedAt());
        }

        /** Whether {@code setting} is still the one that the read was made under. */
        boolean isCurrent(@Nullable PracticeRepositorySetting setting) {
            return setting != null
                    && setting.getRepositoryItemId() == repositoryItemId
                    && setting.getDirectory().equals(directory)
                    && setting.getRevision() == revision
                    && Objects.equals(setting.getUpdatedAt(), settingUpdatedAt);
        }
    }

    /** A practice folder at one commit: its files by their path in the folder, and the setting it was read under. */
    public record Folder(String commit, Map<String, String> files, ReadUnder readUnder) {}

    /**
     * Every practice folder in the directory at one commit, by folder name, and the setting it was read under.
     *
     * @param folders the files of each folder that could be read, by their path in the folder
     * @param unreadable the problems of each folder that holds a link, a file that is too large, or a file that is not
     *     text: it is not a practice now, but it is not gone either
     * @param problems why the directory itself cannot be read. A sync then changes nothing, and both maps are empty.
     */
    public record Tree(
            String commit,
            SortedMap<String, Map<String, String>> folders,
            SortedMap<String, List<PracticeFileProblemDTO>> unreadable,
            List<PracticeFileProblemDTO> problems,
            ReadUnder readUnder) {}

    /**
     * The folder of {@code slug} at the head of the default branch, or empty when the branch has no such folder.
     *
     * @throws ResponseStatusException 409 when the workspace has no practice repository, or it cannot be read now
     * @throws PracticeFolderInvalidException when the folder holds a file that is too large, not text, or a link
     */
    public Optional<Folder> read(long workspaceId, String slug) {
        var setting = setting(workspaceId);
        try (var checkout = checkout(workspaceId, setting)) {
            var root = checkout.directory().toRealPath();
            var folder = root.resolve(setting.getDirectory()).resolve(slug).normalize();
            if (!folder.startsWith(root)) return Optional.empty();
            // A link anywhere on the path makes the folder unreadable, never absent: a sync must not take it for
            // removed.
            if (linkOnPath(root, folder))
                throw invalid(PracticeFileProblemDTO.FILE_INVALID, slug, "The folder or its directory is a link.");
            if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            return Optional.of(new Folder(checkout.head(), files(folder), ReadUnder.of(setting)));
        } catch (IOException failed) {
            throw readFailed(workspaceId, failed);
        }
    }

    /**
     * Every folder in the directory of the practice folders at the head of the default branch. A file beside the
     * folders, such as a README, is not a practice and is skipped. A folder whose name is not a slug is read like any
     * other, so that its check reports the name.
     *
     * @throws ResponseStatusException 409 when the workspace has no practice repository, or it cannot be read now
     */
    public Tree readAll(long workspaceId) {
        var setting = setting(workspaceId);
        var readUnder = ReadUnder.of(setting);
        try (var checkout = checkout(workspaceId, setting)) {
            var root = checkout.directory().toRealPath();
            var directory = root.resolve(setting.getDirectory()).normalize();
            var folders = new TreeMap<String, Map<String, String>>();
            var unreadable = new TreeMap<String, List<PracticeFileProblemDTO>>();
            if (!directory.startsWith(root) || linkOnPath(root, directory))
                return new Tree(
                        checkout.head(),
                        folders,
                        unreadable,
                        List.of(problem(
                                PracticeFileProblemDTO.FILE_INVALID,
                                setting.getDirectory(),
                                "The directory of the practice folders is a link. Make it a directory.")),
                        readUnder);
            // A missing directory is a wrong setting more often than the removal of every practice.
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
                return new Tree(
                        checkout.head(),
                        folders,
                        unreadable,
                        List.of(problem(
                                PracticeFileProblemDTO.DIRECTORY_MISSING,
                                setting.getDirectory(),
                                "The default branch has no directory " + setting.getDirectory()
                                        + ". Commit the practice folders there, or change the directory.")),
                        readUnder);
            List<Path> children;
            try (var list = Files.list(directory)) {
                children = list.filter(child ->
                                Files.isSymbolicLink(child) || Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS))
                        .sorted()
                        .toList();
            }
            if (children.size() > MAX_FOLDERS)
                return new Tree(
                        checkout.head(),
                        new TreeMap<>(),
                        new TreeMap<>(),
                        List.of(problem(
                                PracticeFileProblemDTO.TOO_MANY_FOLDERS,
                                setting.getDirectory(),
                                "Keep at most " + MAX_FOLDERS + " practice folders in the directory.")),
                        readUnder);
            for (var child : children) {
                var name = child.getFileName().toString();
                if (Files.isSymbolicLink(child)) {
                    unreadable.put(
                            name,
                            List.of(problem(
                                    PracticeFileProblemDTO.FILE_INVALID, name, "A practice folder is not a link.")));
                    continue;
                }
                try {
                    folders.put(name, files(child));
                } catch (PracticeFolderInvalidException invalid) {
                    unreadable.put(name, invalid.problems());
                }
            }
            return new Tree(checkout.head(), folders, unreadable, List.of(), readUnder);
        } catch (IOException failed) {
            throw readFailed(workspaceId, failed);
        }
    }

    private PracticeRepositorySetting setting(long workspaceId) {
        return settings.findById(workspaceId)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.CONFLICT, "This workspace has no practice repository."));
    }

    /** A checkout of the default branch's head, or a 409 that one log line records with the workspace and a code. */
    private RepositoryCheckouts.Checkout checkout(long workspaceId, PracticeRepositorySetting setting) {
        var repository = monitored.repositories(workspaceId).get(setting.getRepositoryItemId());
        if (repository == null || repository.defaultBranch() == null) {
            log.warn(
                    "Could not read the practice repository: workspaceId={}, code={}",
                    workspaceId,
                    repository == null ? "REPOSITORY_NOT_MONITORED" : "DEFAULT_BRANCH_UNKNOWN");
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The practice repository is not monitored, or its default branch is not known yet.");
        }
        try {
            return checkouts.checkout(workspaceId, repository.nameWithOwner(), repository.defaultBranch(), null);
        } catch (RepositoryCheckoutException unavailable) {
            log.warn(
                    "Could not read the practice repository: workspaceId={}, code={}", workspaceId, unavailable.code());
            throw new ResponseStatusException(HttpStatus.CONFLICT, reason(unavailable), unavailable);
        }
    }

    private static UncheckedIOException readFailed(long workspaceId, IOException failed) {
        log.warn("Could not read the practice repository: workspaceId={}, code=READ_FAILED", workspaceId);
        return new UncheckedIOException(failed);
    }

    private static boolean linkOnPath(Path root, Path path) {
        for (Path step = path; step != null && !step.equals(root); step = step.getParent())
            if (Files.isSymbolicLink(step)) return true;
        return false;
    }

    private static Map<String, String> files(Path folder) throws IOException {
        var files = new TreeMap<String, String>();
        long total = 0;
        try (var walk = Files.walk(folder)) {
            for (var paths = walk.iterator(); paths.hasNext(); ) {
                var path = paths.next();
                if (path.equals(folder) || Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                // The path's own names, joined with "/": a file name that holds a backslash stays itself, and never
                // becomes the path of another file in the folder.
                var name = StreamSupport.stream(folder.relativize(path).spliterator(), false)
                        .map(Path::toString)
                        .collect(Collectors.joining("/"));
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    throw invalid(PracticeFileProblemDTO.FILE_INVALID, name, "A practice folder holds no links.");
                if (files.size() >= MAX_FILES)
                    throw invalid(
                            PracticeFileProblemDTO.FOLDER_TOO_LARGE, name, "A practice folder holds a few files.");
                long size = Files.size(path);
                if (size > MAX_FILE_BYTES)
                    throw invalid(PracticeFileProblemDTO.FILE_TOO_LARGE, name, "Keep each file under 128 KiB.");
                total += size;
                if (total > MAX_FOLDER_BYTES)
                    throw invalid(
                            PracticeFileProblemDTO.FOLDER_TOO_LARGE, name, "Keep a practice folder under 256 KiB.");
                files.put(name, text(path, name));
            }
        }
        return files;
    }

    private static String reason(RepositoryCheckoutException unavailable) {
        return switch (unavailable.code()) {
            case REPOSITORY_NOT_VISIBLE ->
                "Hephaestus cannot see the practice repository. Check that the workspace still monitors it.";
            case REF_NOT_FOUND -> "The default branch of the practice repository has no commit.";
            case MIRROR_UNAVAILABLE -> "The practice repository cannot be fetched now. Try again later.";
        };
    }

    private static String text(Path path, String name) throws IOException {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(Files.readAllBytes(path)))
                    .toString();
        } catch (CharacterCodingException binary) {
            throw new PracticeFolderInvalidException(
                    List.of(new PracticeFileProblemDTO(
                            name,
                            null,
                            null,
                            PracticeFileProblemDTO.FILE_INVALID,
                            "Each file of a practice folder is UTF-8 text.")),
                    binary);
        }
    }

    private static PracticeFolderInvalidException invalid(String code, String file, String message) {
        return new PracticeFolderInvalidException(List.of(problem(code, file, message)));
    }

    private static PracticeFileProblemDTO problem(String code, String file, String message) {
        return new PracticeFileProblemDTO(file, null, null, code, message);
    }
}
