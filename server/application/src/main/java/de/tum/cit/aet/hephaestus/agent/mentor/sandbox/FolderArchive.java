package de.tum.cit.aet.hephaestus.agent.mentor.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeSet;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.springframework.util.StreamUtils;

/**
 * The tar streams that the server stages into a tools Pod. They keep a captured file's executable mode. The sandbox
 * runtime owns what staging means for the sandbox user: the staged folder is read-only to that user
 * ({@code ExecSandboxes}).
 */
final class FolderArchive {

    private FolderArchive() {}

    /** Files by their path in the folder, with their parent directories. */
    static byte[] files(Map<String, byte[]> files) {
        var bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = open(bytes)) {
            var directories = new TreeSet<String>();
            for (String path : files.keySet()) {
                for (int slash = path.indexOf('/'); slash > 0; slash = path.indexOf('/', slash + 1)) {
                    directories.add(path.substring(0, slash + 1));
                }
            }
            for (String directory : directories) {
                var entry = new TarArchiveEntry(directory);
                entry.setMode(0755);
                tar.putArchiveEntry(entry);
                tar.closeArchiveEntry();
            }
            for (var file : files.entrySet()) {
                var entry = new TarArchiveEntry(file.getKey());
                entry.setMode(0644);
                entry.setSize(file.getValue().length);
                tar.putArchiveEntry(entry);
                tar.write(file.getValue());
                tar.closeArchiveEntry();
            }
            tar.finish();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not pack the context documents", e);
        }
        return bytes.toByteArray();
    }

    /** Deterministic tar bytes from a retained directory; never closes the caller's output stream. */
    static long directory(Path source, OutputStream out) throws IOException {
        try (TarArchiveOutputStream tar = open(StreamUtils.nonClosing(out));
                var paths = Files.walk(source)) {
            for (Path path : paths.filter(path -> !path.equals(source))
                    .sorted(Comparator.comparing(
                            path -> source.relativize(path).toString().replace('\\', '/')))
                    .toList()) {
                String name = source.relativize(path).toString().replace('\\', '/');
                TarArchiveEntry entry;
                if (Files.isSymbolicLink(path)) {
                    entry = new TarArchiveEntry(name, TarConstants.LF_SYMLINK);
                    entry.setLinkName(Files.readSymbolicLink(path).toString());
                } else if (Files.isDirectory(path)) {
                    entry = new TarArchiveEntry(name + "/");
                    entry.setMode(0755);
                } else if (Files.isRegularFile(path)) {
                    entry = new TarArchiveEntry(name);
                    entry.setMode(Files.isExecutable(path) ? 0755 : 0644);
                    entry.setSize(Files.size(path));
                } else continue;
                entry.setModTime(0);
                tar.putArchiveEntry(entry);
                if (entry.isFile() && !entry.isSymbolicLink()) Files.copy(path, tar);
                tar.closeArchiveEntry();
            }
            tar.finish();
            return tar.getBytesWritten();
        }
    }

    private static TarArchiveOutputStream open(OutputStream out) {
        var tar = new TarArchiveOutputStream(out);
        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
        tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
        return tar;
    }
}
