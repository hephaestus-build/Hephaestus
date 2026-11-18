package de.tum.cit.aet.hephaestus.practices.repository;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.MonitoredRepositoryQuery;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryCheckoutException;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryCheckouts;
import de.tum.cit.aet.hephaestus.practices.PracticeFolderInvalidException;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeFileProblemDTO;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.io.UncheckedIOException;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Reads practice folders from the head of the practice repository's default branch. The checkout holds no credential,
 * and the reader follows no symbolic link, so a folder can only name files inside it.
 */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class PracticeRepositoryFolders {
    /** More than a folder may hold, so a reader stops before it loads a large tree into memory. */
    private static final int MAX_FILES = 64;

    private static final long MAX_FILE_BYTES = 256 * 1024;

    private final PracticeRepositorySettingRepository settings;
    private final MonitoredRepositoryQuery monitored;
    private final RepositoryCheckouts checkouts;

    /** A practice folder at one commit: its files by their path in the folder. */
    public record Folder(String commit, Map<String, String> files) {}

    /**
     * The folder of {@code slug} at the head of the default branch, or empty when the branch has no such folder.
     *
     * @throws ResponseStatusException 409 when the workspace has no practice repository, or it cannot be read now
     * @throws PracticeFolderInvalidException when the folder holds a file that is too large, not text, or a link
     */
    public Optional<Folder> read(long workspaceId, String slug) {
        var setting = settings.findById(workspaceId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "This workspace has no practice repository."));
        var repository = monitored.repositories(workspaceId).get(setting.getRepositoryItemId());
        if (repository == null || repository.defaultBranch() == null)
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The practice repository is not monitored, or its default branch is not known yet.");
        try (var checkout = checkouts.checkout(
                workspaceId, repository.nameWithOwner(), repository.defaultBranch(), null)) {
            var root = checkout.directory().toRealPath();
            var folder = root.resolve(setting.getDirectory()).resolve(slug).normalize();
            if (!folder.startsWith(root) || !Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS))
                return Optional.empty();
            if (!folder.toRealPath().equals(folder)) throw invalid(PracticeFileProblemDTO.FILE_INVALID, slug, "The folder is a link.");
            return Optional.of(new Folder(checkout.head(), files(folder)));
        } catch (RepositoryCheckoutException unavailable) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The practice repository cannot be read now. Try again later.",
                    unavailable);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private static Map<String, String> files(Path folder) throws IOException {
        var files = new TreeMap<String, String>();
        try (var walk = Files.walk(folder)) {
            for (var path : walk.toList()) {
                if (path.equals(folder) || Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                var name = folder.relativize(path).toString().replace('\\', '/');
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    throw invalid(PracticeFileProblemDTO.FILE_INVALID, name, "A practice folder holds no links.");
                if (files.size() >= MAX_FILES)
                    throw invalid(PracticeFileProblemDTO.FOLDER_TOO_LARGE, name, "A practice folder holds a few files.");
                if (Files.size(path) > MAX_FILE_BYTES)
                    throw invalid(PracticeFileProblemDTO.FILE_TOO_LARGE, name, "Keep each file under 128 KiB.");
                files.put(name, text(path, name));
            }
        }
        return files;
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
            throw invalid(PracticeFileProblemDTO.FILE_INVALID, name, "Each file of a practice folder is UTF-8 text.");
        }
    }

    private static PracticeFolderInvalidException invalid(String code, String file, String message) {
        return new PracticeFolderInvalidException(List.of(new PracticeFileProblemDTO(file, null, null, code, message)));
    }
}
