package de.tum.cit.aet.hephaestus.agent.mentor.context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;

/**
 * The tar streams of a turn's context folder that the conversation loop puts into its tools Job. They keep a captured
 * file's executable mode.
 */
public final class FolderArchive {

    private FolderArchive() {}

    /** Files by their path in the folder, with their parent directories. */
    static byte[] files(Map<String, byte[]> files, List<String> destinations) {
        var bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = open(bytes)) {
            var directories = new TreeSet<String>();
            directories.add("repos/");
            for (String destination : destinations) {
                String canonical = ContextFolderPaths.relative(destination)
                        .filter(path -> path.equals(destination) && path.startsWith("repos/"))
                        .orElseThrow(
                                () -> new IllegalArgumentException("A context loader named an invalid destination"));
                // The upload creates its leaf. All of its ancestors must exist before it starts.
                for (int slash = canonical.indexOf('/'); slash > 0; slash = canonical.indexOf('/', slash + 1)) {
                    directories.add(canonical.substring(0, slash + 1));
                }
            }
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

    /** A directory tree, with paths relative to it, into a temporary file the caller deletes. */
    public static Path directory(Path source) throws IOException {
        Path archive = Files.createTempFile("heph-load-", ".tar");
        try (OutputStream out = Files.newOutputStream(archive);
                TarArchiveOutputStream tar = open(out)) {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                        throws IOException {
                    if (!directory.equals(source)) {
                        var entry = new TarArchiveEntry(name(directory) + "/");
                        entry.setMode(0755);
                        tar.putArchiveEntry(entry);
                        tar.closeArchiveEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (attributes.isSymbolicLink()) {
                        // A link stays a link: in the sandbox it reaches only what the sandbox holds.
                        var entry = new TarArchiveEntry(name(file), TarConstants.LF_SYMLINK);
                        entry.setLinkName(Files.readSymbolicLink(file).toString());
                        tar.putArchiveEntry(entry);
                        tar.closeArchiveEntry();
                    } else if (attributes.isRegularFile()) {
                        var entry = new TarArchiveEntry(name(file));
                        entry.setMode(Files.isExecutable(file) ? 0755 : 0644);
                        entry.setSize(attributes.size());
                        tar.putArchiveEntry(entry);
                        Files.copy(file, tar);
                        tar.closeArchiveEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }

                private String name(Path path) {
                    return source.relativize(path).toString().replace('\\', '/');
                }
            });
            tar.finish();
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(archive);
            throw e;
        }
        return archive;
    }

    private static TarArchiveOutputStream open(OutputStream out) {
        var tar = new TarArchiveOutputStream(out);
        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
        tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
        return tar;
    }
}
