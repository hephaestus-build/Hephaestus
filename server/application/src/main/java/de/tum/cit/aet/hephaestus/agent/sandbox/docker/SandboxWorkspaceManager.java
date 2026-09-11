package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import de.tum.cit.aet.hephaestus.agent.context.EvidenceDirectory;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.io.IOUtils;

/** Creates the trusted workspace archive streamed through the runtime gateway. */
public class SandboxWorkspaceManager {
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private static long copyFilePrefix(Path source, OutputStream out, long limit) throws IOException {
        try (InputStream in = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
            return IOUtils.copyLarge(in, out, 0, limit);
        }
    }

    public Path createInputTar(Map<String, byte[]> files, Map<String, Path> filesOnDisk) throws IOException {
        return createInputTar(files, filesOnDisk, List.of());
    }

    public Path createInputTar(
            Map<String, byte[]> files, Map<String, Path> filesOnDisk, List<EvidenceDirectory> directories)
            throws IOException {
        validateDirectories(directories);
        Path path = Files.createTempFile("sandbox-inputs-", ".tar");
        try {
            writeInputTar(path, files, filesOnDisk, directories);
            return path;
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(path);
            throw exception;
        }
    }

    private void writeInputTar(
            Path tarFile, Map<String, byte[]> files, Map<String, Path> filesOnDisk, List<EvidenceDirectory> directories)
            throws IOException {
        try (OutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(tarFile), COPY_BUFFER_SIZE);
                TarArchiveOutputStream tar = new TarArchiveOutputStream(fileOut)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);

            Set<String> allPaths = new TreeSet<>();
            for (var path : files.keySet()) {
                String safe = validatePath(path);
                coveredByDirectory(safe, null, directories);
                if (!allPaths.add(safe)) throw new SandboxException("Duplicate workspace input path");
            }
            for (var entry : filesOnDisk.entrySet()) {
                String safe = validatePath(entry.getKey());
                if (!coveredByDirectory(safe, entry.getValue(), directories) && !allPaths.add(safe)) {
                    throw new SandboxException("Duplicate workspace input path");
                }
            }
            for (var directory : directories)
                allPaths.add(directory.target().substring(0, directory.target().length() - 1));
            for (String path : allPaths) {
                for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) {
                    if (allPaths.contains(path.substring(0, slash)))
                        throw new SandboxException("Workspace input path collision");
                }
            }
            for (String dir : ancestorDirs(allPaths)) {
                TarArchiveEntry dirEntry = new TarArchiveEntry(dir + "/");
                dirEntry.setModTime(System.currentTimeMillis());
                boolean writable = isWritableRegion(dir + "/");
                dirEntry.setUserId(writable ? 1000 : 0);
                dirEntry.setGroupId(writable ? 1000 : 0);
                dirEntry.setMode(writable ? 0755 : 0555);
                tar.putArchiveEntry(dirEntry);
                tar.closeArchiveEntry();
            }

            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                TarArchiveEntry tarEntry = newInputEntry(validatePath(entry.getKey()), entry.getValue().length);
                tar.putArchiveEntry(tarEntry);
                tar.write(entry.getValue());
                tar.closeArchiveEntry();
            }

            for (Map.Entry<String, Path> entry : filesOnDisk.entrySet()) {
                String safe = validatePath(entry.getKey());
                if (!coveredByDirectory(safe, entry.getValue(), directories))
                    writeDiskFile(tar, safe, entry.getValue());
            }
            for (var directory : directories) {
                Files.walkFileTree(directory.source(), new SimpleFileVisitor<>() {
                    private String target(Path path) {
                        String suffix = directory.source().relativize(path).toString();
                        return directory.target() + suffix;
                    }

                    @Override
                    public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes)
                            throws IOException {
                        String target = target(path);
                        var entry = new TarArchiveEntry(target.endsWith("/") ? target : target + "/");
                        entry.setMode(isWritableRegion(target) ? 0755 : 0555);
                        tar.putArchiveEntry(entry);
                        tar.closeArchiveEntry();
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                        if (!attributes.isRegularFile())
                            throw new SandboxException("Workspace directories may contain only regular files");
                        writeDiskFile(tar, target(path), path);
                        return FileVisitResult.CONTINUE;
                    }
                });
            }

            tar.finish();
        }
    }

    private static void validateDirectories(List<EvidenceDirectory> directories) {
        for (int index = 0; index < directories.size(); index++) {
            var directory = directories.get(index);
            if (!Files.isDirectory(directory.source(), LinkOption.NOFOLLOW_LINKS)) {
                throw new SandboxException("Workspace directory source must be a directory, not a link");
            }
            for (int other = 0; other < index; other++) {
                String target = directories.get(other).target();
                if (target.startsWith(directory.target()) || directory.target().startsWith(target)) {
                    throw new SandboxException("Overlapping workspace directories");
                }
            }
        }
    }

    private static boolean coveredByDirectory(
            String path, @org.jspecify.annotations.Nullable Path source, List<EvidenceDirectory> directories) {
        for (var directory : directories) {
            if (path.startsWith(directory.target())) {
                Path expected = directory
                        .source()
                        .resolve(path.substring(directory.target().length()));
                if (source == null || !source.toAbsolutePath().normalize().equals(expected)) {
                    throw new SandboxException("Workspace file conflicts with a captured directory");
                }
                return true;
            }
            if (directory.target().startsWith(path + "/"))
                throw new SandboxException("Workspace file conflicts with a directory target");
        }
        return false;
    }

    private static void writeDiskFile(TarArchiveOutputStream tar, String target, Path source) throws IOException {
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
            throw new SandboxException("Workspace input must be a regular file");
        long size = Files.size(source);
        var entry = newInputEntry(target, size);
        if (Files.isExecutable(source)) entry.setMode(entry.getMode() | 0111);
        tar.putArchiveEntry(entry);
        if (copyFilePrefix(source, tar, size) != size)
            throw new SandboxException("Workspace input changed while streaming");
        tar.closeArchiveEntry();
    }

    private static TarArchiveEntry newInputEntry(String safePath, long size) {
        TarArchiveEntry entry = new TarArchiveEntry(safePath);
        entry.setSize(size);
        entry.setModTime(System.currentTimeMillis());
        boolean writable = isWritableRegion(safePath);
        entry.setUserId(writable ? 1000 : 0);
        entry.setGroupId(writable ? 1000 : 0);
        entry.setMode(writable ? 0644 : 0444);
        return entry;
    }

    private static boolean isWritableRegion(String path) {
        return (path.startsWith(SandboxLayout.WORK_PREFIX)
                || path.startsWith(SandboxLayout.PI_AGENT_PREFIX)
                || path.startsWith(SandboxLayout.SESSIONS_DIR_PREFIX)
                || path.startsWith(SandboxLayout.OUTPUT_PREFIX));
    }

    private static SortedSet<String> ancestorDirs(Set<String> keys) {
        SortedSet<String> dirs = new TreeSet<>();
        for (String key : keys) {
            for (int slash = key.indexOf('/'); slash >= 0; slash = key.indexOf('/', slash + 1)) {
                dirs.add(key.substring(0, slash));
            }
        }
        return dirs;
    }

    /** Returns a normalized relative path that does not escape the archive root (tar-slip). */
    private static String validatePath(String path) {
        if (path == null || path.isEmpty()) {
            throw new SandboxException("File path must not be empty");
        }
        Path normalized = Path.of(path).normalize();
        if (normalized.isAbsolute()) {
            throw new SandboxException("Absolute paths are not allowed: " + path);
        }
        if (normalized.toString().isEmpty() || normalized.toString().equals(".") || normalized.startsWith("..")) {
            throw new SandboxException("Path traversal detected: " + path);
        }
        return normalized.toString();
    }
}
