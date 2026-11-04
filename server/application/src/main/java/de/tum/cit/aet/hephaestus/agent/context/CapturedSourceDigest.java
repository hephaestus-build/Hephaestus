package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.evidence.CapturedSource;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * One digest of what a capture holds for a set of source kinds, without the times of the capture itself. Two captures
 * of the same words, checks and threads have equal digests, which makes this the smallest form of the source
 * fingerprint of ADR 0069. Capture computes it because only capture has every renderer's bytes.
 *
 * <p>Equal digests must mean equal content. So the list below names only when Hephaestus read a record, never a time
 * that a person or a provider gave the work. A value that moves while the content stays equal only runs a practice
 * again.
 */
public final class CapturedSourceDigest {
    private static final Set<String> CAPTURE_TIMES = Set.of(
            "basis",
            "captured_at",
            "capturedAt",
            "mirror_synced_at",
            "synced_at",
            "observed_at",
            "observedAt",
            "head_check_observed_at",
            "sourceEffectiveAt");

    private static final String CHECKOUTS = "repos/";

    private CapturedSourceDigest() {}

    public static String of(ObjectMapper mapper, PreparedEvidence evidence, Collection<SourceKind> kinds) {
        var manifest = Objects.requireNonNull(evidence.manifest());
        var captures = manifest.sources();
        var canonical = new StringBuilder(manifest.contractVersion().toString()).append('\0');
        kinds.stream()
                .distinct()
                .sorted(Comparator.comparing(SourceKind::value))
                .forEach(kind -> {
                    canonical.append(kind.value()).append('\0');
                    // A checkout is named by the commit and tree in its state. Its file of refs moves with any push.
                    captures.stream()
                            .filter(capture -> capture.kind().equals(kind))
                            .forEach(capture -> {
                                canonical.append(withoutCaptureTimes(mapper.valueToTree(capture.state())));
                                capture.files().stream()
                                        .filter(file -> !file.path().startsWith(CHECKOUTS))
                                        .sorted(Comparator.comparing(CapturedSource::path))
                                        .forEach(file -> canonical
                                                .append('\0')
                                                .append(file.path())
                                                .append('\0')
                                                .append(content(mapper, evidence, file)));
                            });
                });
        return ProvenanceDigest.sha256Hex(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** A structured record without its capture times; any other file by the digest the manifest already holds. */
    private static String content(ObjectMapper mapper, PreparedEvidence evidence, CapturedSource file) {
        boolean lines = file.path().endsWith(".jsonl");
        if (!lines && !file.path().endsWith(".json")) return file.sha256();
        byte[] bytes = bytes(evidence, file);
        if (bytes == null) return file.sha256();
        String text = new String(bytes, StandardCharsets.UTF_8);
        try {
            var canonical = new StringBuilder();
            for (String row : lines ? text.lines().toList() : List.of(text))
                canonical.append(withoutCaptureTimes(mapper.readTree(row))).append('\n');
            return canonical.toString();
        } catch (JacksonException unreadable) {
            return file.sha256();
        }
    }

    private static byte @Nullable [] bytes(PreparedEvidence evidence, CapturedSource file) {
        byte[] held = evidence.files().get(file.path());
        if (held != null) return held;
        var path = evidence.filesOnDisk().get(file.path());
        if (path == null) return null;
        try {
            return Files.readAllBytes(path);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static JsonNode withoutCaptureTimes(JsonNode node) {
        if (node instanceof ObjectNode object) object.remove(CAPTURE_TIMES);
        for (JsonNode child : node) withoutCaptureTimes(child);
        return node;
    }
}
