package de.tum.cit.aet.hephaestus.agent.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.io.input.BoundedInputStream;

/**
 * Reads bounded output files without filesystem extraction. Metadata extensions are refused before
 * Commons Compress processes them, preventing hidden entries and sparse expansion from evading limits.
 * A writer therefore has to stay inside USTAR: every archive member name, {@code out/} prefix included,
 * is at most {@link org.apache.commons.compress.archivers.tar.TarConstants#NAMELEN} ASCII bytes.
 */
public final class SandboxOutputArchive {

    public static final long MAX_OUTPUT_BYTES = 50L * 1024 * 1024;
    public static final long MAX_SINGLE_FILE_BYTES = 10L * 1024 * 1024;
    public static final int MAX_ENTRIES = 10_000;

    private final long maxArchiveBytes;
    private final long maxOutputBytes;
    private final long maxFileBytes;
    private final int maxEntries;

    public SandboxOutputArchive() {
        this(MAX_OUTPUT_BYTES, MAX_OUTPUT_BYTES, MAX_SINGLE_FILE_BYTES, MAX_ENTRIES);
    }

    public SandboxOutputArchive(long maxArchiveBytes, long maxOutputBytes, long maxFileBytes, int maxEntries) {
        if (maxArchiveBytes <= 0
                || maxArchiveBytes == Long.MAX_VALUE
                || maxOutputBytes <= 0
                || maxFileBytes <= 0
                || maxFileBytes > Integer.MAX_VALUE
                || maxEntries <= 0) {
            throw new IllegalArgumentException("Archive limits must be positive and file sizes must fit in memory");
        }
        this.maxArchiveBytes = maxArchiveBytes;
        this.maxOutputBytes = maxOutputBytes;
        this.maxFileBytes = maxFileBytes;
        this.maxEntries = maxEntries;
    }

    /** Reads files beneath the requested root, permits empty directory headers, and closes the stream. */
    public Map<String, byte[]> read(InputStream input, String root) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        Set<String> paths = new HashSet<>();
        long totalBytes = 0;
        // One extra byte distinguishes a body at the limit from a truncated over-limit body.
        try (BoundedInputStream bounded = BoundedInputStream.builder()
                        .setInputStream(input)
                        .setMaxCount(Math.addExact(maxArchiveBytes, 1))
                        .get();
                var tar = new RegularTarInputStream(bounded, maxEntries)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String name = relativeName(entry.getName(), root, entry.isDirectory());
                if (!paths.add(name)) {
                    throw new IOException("Duplicate output archive path");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                long size = entry.getSize();
                if (size < 0 || size > maxFileBytes || size > maxOutputBytes - totalBytes) {
                    throw new IOException("Output archive exceeds extracted size limit");
                }
                byte[] bytes = tar.readNBytes((int) size);
                if (bytes.length != size) {
                    throw new IOException("Truncated output archive entry");
                }
                totalBytes += bytes.length;
                files.put(name, bytes);
            }
            // Commons Compress stops at the tar end marker, not the end of the HTTP/Docker body.
            // Bound and validate that remainder too, rather than accepting a second hidden archive.
            byte[] buffer = new byte[8192];
            int count;
            while ((count = bounded.read(buffer)) != -1) {
                for (int i = 0; i < count; i++) {
                    if (buffer[i] != 0) {
                        throw new IOException("Unexpected data after output archive");
                    }
                }
            }
            if (bounded.getCount() > maxArchiveBytes) {
                throw new IOException("Output archive exceeds wire size limit");
            }
        }
        return files;
    }

    private static String relativeName(String name, String root, boolean directory) throws IOException {
        try {
            Path path = Path.of(name);
            // Reject traversal components even when normalization would conceal them.
            if (path.isAbsolute() || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0) {
                throw new IOException("Unsafe output archive path");
            }
            for (Path component : path) {
                if (component.toString().equals("..")) {
                    throw new IOException("Unsafe output archive path");
                }
            }
            Path normalized = path.normalize();
            if (!normalized.startsWith(root) || (!directory && normalized.getNameCount() < 2)) {
                throw new IOException("Output archive entry is outside its root");
            }
            return Path.of(root).relativize(normalized).toString();
        } catch (InvalidPathException e) {
            throw new IOException("Invalid output archive path", e);
        }
    }

    private static final class RegularTarInputStream extends TarArchiveInputStream {

        private final int maxEntries;
        private int entries;
        private int endRecords;

        private RegularTarInputStream(InputStream input, int maxEntries) {
            // No larger block padding is consumed invisibly after the end records.
            super(input, TarConstants.DEFAULT_RCDSIZE);
            this.maxEntries = maxEntries;
        }

        @Override
        protected byte[] readRecord() throws IOException {
            byte[] record = super.readRecord();
            if (record == null) {
                throw new IOException("Missing output archive end records");
            }
            if (isEOFRecord(record)) {
                endRecords++;
                return record;
            }
            if (endRecords != 0 || ++entries > maxEntries) {
                throw new IOException("Invalid output archive or entry count limit exceeded");
            }
            TarArchiveEntry header;
            try {
                header = new TarArchiveEntry(record);
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid output archive header", e);
            }
            if (!header.isCheckSumOK()) {
                throw new IOException("Output archive header has an invalid checksum");
            }
            byte type = header.getLinkFlag();
            if (isNameExtension(type)) {
                throw new IOException("Output archive paths must be at most " + TarConstants.NAMELEN
                        + " ASCII bytes. The server does not read tar name extension records.");
            }
            boolean regular =
                    (type == TarConstants.LF_NORMAL || type == TarConstants.LF_OLDNORM) && !header.isDirectory();
            boolean directory = type == TarConstants.LF_DIR && header.getSize() == 0;
            if (!regular && !directory) {
                throw new IOException("Output archive requires regular files");
            }
            return record;
        }

        /**
         * A producer encodes a name longer than {@link TarConstants#NAMELEN} bytes, or a name outside
         * ASCII, as one of these records. Reading them would hand Commons Compress the PAX keys that
         * expand a sparse entry past the size its header declares, so writers stay inside USTAR instead.
         */
        private static boolean isNameExtension(byte type) {
            return type == TarConstants.LF_PAX_EXTENDED_HEADER_LC
                    || type == TarConstants.LF_PAX_EXTENDED_HEADER_UC
                    || type == TarConstants.LF_PAX_GLOBAL_EXTENDED_HEADER
                    || type == TarConstants.LF_GNUTYPE_LONGNAME
                    || type == TarConstants.LF_GNUTYPE_LONGLINK;
        }
    }
}
