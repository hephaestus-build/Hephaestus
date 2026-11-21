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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    /** The limit that {@code PracticeFiles} applies, checked before a file is read into memory. */
    private static final long MAX_FILE_BYTES = 128 * 1024;

    private final PracticeRepositorySettingRepository settings;
    private final MonitoredRepositoryQuery monitored;
    private final RepositoryCheckouts checkouts;

    /**
     * A practice folder at one commit: its files by their path in the folder, and the setting it was read under.
     *
     * @param revision the setting's revision, which a set or unset changes
     */
    public record Folder(
            String commit, Map<String, String> files, long repositoryItemId, String directory, long revision) {}

    /**
     * The folder of {@code slug} at the head of the default branch, or empty when the branch has no such folder.
     *
     * @throws ResponseStatusException 409 when the workspace has no practice repository, or it cannot be read now
     * @throws PracticeFolderInvalidException when the folder holds a file that is too large, not text, or a link
     */
    public Optional<Folder> read(long workspaceId, String slug) {
        var setting = settings.findById(workspaceId)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.CONFLICT, "This workspace has no practice repository."));
        var repository = monitored.repositories(workspaceId).get(setting.getRepositoryItemId());
        if (repository == null || repository.defaultBranch() == null)
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The practice repository is not monitored, or its default branch is not known yet.");
        try (var checkout =
                checkouts.checkout(workspaceId, repository.nameWithOwner(), repository.defaultBranch(), null)) {
            var root = checkout.directory().toRealPath();
            var folder = root.resolve(setting.getDirectory()).resolve(slug).normalize();
            if (!folder.startsWith(root)) return Optional.empty();
            // A link anywhere on the path makes the folder unreadable, never absent: a sync must not take it for
            // removed.
            for (Path path = folder; path != null && !path.equals(root); path = path.getParent())
                if (Files.isSymbolicLink(path))
                    throw invalid(PracticeFileProblemDTO.FILE_INVALID, slug, "The folder or its directory is a link.");
            if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            return Optional.of(new Folder(
                    checkout.head(),
                    files(folder),
                    setting.getRepositoryItemId(),
                    setting.getDirectory(),
                    setting.getRevision()));
        } catch (RepositoryCheckoutException unavailable) {
            log.warn(
                    "Could not read the practice repository: workspaceId={}, code={}", workspaceId, unavailable.code());
            throw new ResponseStatusException(HttpStatus.CONFLICT, reason(unavailable), unavailable);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private static Map<String, String> files(Path folder) throws IOException {
        var files = new TreeMap<String, String>();
        try (var walk = Files.walk(folder)) {
            for (var paths = walk.iterator(); paths.hasNext(); ) {
                var path = paths.next();
                if (path.equals(folder) || Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                var name = folder.relativize(path).toString().replace('\\', '/');
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    throw invalid(PracticeFileProblemDTO.FILE_INVALID, name, "A practice folder holds no links.");
                if (files.size() >= MAX_FILES)
                    throw invalid(
                            PracticeFileProblemDTO.FOLDER_TOO_LARGE, name, "A practice folder holds a few files.");
                if (Files.size(path) > MAX_FILE_BYTES)
                    throw invalid(PracticeFileProblemDTO.FILE_TOO_LARGE, name, "Keep each file under 128 KiB.");
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
        return new PracticeFolderInvalidException(List.of(new PracticeFileProblemDTO(file, null, null, code, message)));
    }
}
