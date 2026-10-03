package de.tum.cit.aet.hephaestus.agent.runtime;

import de.tum.cit.aet.hephaestus.agent.context.EvidenceDirectory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

public final class ProvenanceDigest {

    private ProvenanceDigest() {}

    public static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(newSha256().digest(bytes));
    }

    /** Digest of everything left on {@code input}, which is consumed but not closed. */
    public static String sha256Hex(InputStream input) throws IOException {
        MessageDigest digest = newSha256();
        new DigestInputStream(input, digest).transferTo(OutputStream.nullOutputStream());
        return HexFormat.of().formatHex(digest.digest());
    }

    /** A fresh SHA-256 for a caller that has to digest while it reads. */
    public static MessageDigest sha256() {
        return newSha256();
    }

    public static String hex(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Root digest over a set of workspace files: SHA-256 over the path-sorted sequence of
     * {@code path NUL sha256(content) LF} entries. Deterministic and iteration-order independent, so the same
     * files always digest identically regardless of the map that carried them.
     */
    public static String rootDigestHex(Map<String, byte[]> files) {
        return rootDigestHex(files, content -> sha256Hex(content));
    }

    /**
     * Hashes sandbox inputs after removing the run UUID so otherwise-identical runs share a digest. Only the
     * lowercase hyphenated form produced by {@link UUID#toString()} is elided.
     */
    public static String inputsDigestHex(Map<String, byte[]> files, UUID jobId) {
        byte[] jobIdBytes = jobId.toString().getBytes(StandardCharsets.UTF_8);
        return rootDigestHex(files, content -> elidedContentDigestHex(content, jobIdBytes));
    }

    /** Hashes the complete folder, including every object of its full-history repository checkouts. */
    public static String inputsDigestHex(
            Map<String, byte[]> scaffolding, Map<String, Path> files, List<EvidenceDirectory> directories, UUID jobId) {
        var paths = new TreeMap<>(files);
        try {
            for (var directory : directories) {
                try (var entries = Files.walk(directory.source())) {
                    for (var file : entries.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                            .toList()) {
                        String path = directory.target()
                                + directory.source().relativize(file).toString().replace('\\', '/');
                        var previous = paths.putIfAbsent(path, file);
                        if (previous != null && !previous.equals(file)) {
                            throw new IllegalArgumentException("Conflicting folder input: " + path);
                        }
                    }
                }
            }
            var hashes = new TreeMap<String, String>();
            for (var entry : paths.entrySet()) {
                try (var input = Files.newInputStream(entry.getValue())) {
                    // Attempt-specific task metadata is small; source records and Git objects are streamed exactly.
                    hashes.put(
                            entry.getKey(),
                            entry.getKey().equals(SandboxLayout.TASK_ENVELOPE_FILENAME)
                                    ? elidedContentDigestHex(
                                            input.readAllBytes(),
                                            jobId.toString().getBytes(StandardCharsets.UTF_8))
                                    : sha256Hex(input));
                }
            }
            for (var entry : scaffolding.entrySet()) {
                if (hashes.putIfAbsent(
                                entry.getKey(),
                                elidedContentDigestHex(
                                        entry.getValue(), jobId.toString().getBytes(StandardCharsets.UTF_8)))
                        != null) {
                    throw new IllegalArgumentException("Scaffolding overlaps the job folder: " + entry.getKey());
                }
            }
            MessageDigest digest = newSha256();
            for (var entry : hashes.entrySet()) {
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return hex(digest);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String rootDigestHex(Map<String, byte[]> files, ContentDigest contentDigest) {
        MessageDigest digest = newSha256();
        for (Map.Entry<String, byte[]> entry : new TreeMap<>(files).entrySet()) {
            digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(contentDigest.of(entry.getValue()).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    @FunctionalInterface
    private interface ContentDigest {
        String of(byte[] content);
    }

    /**
     * Digest of {@code content} split on every occurrence of {@code needle}, each surviving segment fed
     * length-prefixed. Two contents that differ only in what sat at those occurrences digest alike; anything
     * else does not. Length-prefixing rather than substituting a placeholder is what makes that exact: no
     * segment can be read as its neighbour, and no literal in the content can impersonate an elided id.
     */
    private static String elidedContentDigestHex(byte[] content, byte[] needle) {
        MessageDigest digest = newSha256();
        int segmentStart = 0;
        int i = 0;
        while (i <= content.length - needle.length) {
            if (startsWith(content, i, needle)) {
                updateSegment(digest, content, segmentStart, i);
                i += needle.length;
                segmentStart = i;
            } else {
                i++;
            }
        }
        updateSegment(digest, content, segmentStart, content.length);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateSegment(MessageDigest digest, byte[] content, int from, int to) {
        int length = to - from;
        digest.update(
                new byte[] {(byte) (length >>> 24), (byte) (length >>> 16), (byte) (length >>> 8), (byte) length});
        digest.update(content, from, length);
    }

    private static boolean startsWith(byte[] haystack, int offset, byte[] needle) {
        for (int i = 0; i < needle.length; i++) {
            if (haystack[offset + i] != needle[i]) {
                return false;
            }
        }
        return true;
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
