package de.tum.cit.aet.hephaestus.evidence;

import de.tum.cit.aet.hephaestus.evidence.capture.CaptureContribution;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Content fingerprints shared by capture and reads of the current source. A fingerprint names what the work holds,
 * never how much of it the mirror had read: a sync that finishes between a capture and a later read changes the
 * mirror's completeness and its {@code truncated} flags, not the work, so neither is fingerprinted. New content that
 * such a sync brings changes the records themselves.
 */
public final class SourceFingerprint {
    private static final String RENDERER_VERSION = "2";
    /** What a rendered record says about the mirror's read of the work rather than about the work. */
    private static final Set<String> OBSERVATIONS = Set.of("basis", "truncated");

    private SourceFingerprint() {}

    public static @Nullable String of(
            ObjectMapper mapper, SourceKind kind, SourceCaptureState state, Map<String, byte[]> files) {
        if (!(state instanceof SourceCaptureState.Available available)) return null;
        String identity = available.facts().immutableIdentity();
        if (contentAddressed(kind)) return identity == null ? null : git(kind, identity);
        var content = JsonNodeFactory.instance.objectNode();
        content.put("kind", kind.value());
        content.put("renderer", RENDERER_VERSION);
        if (identity != null && kind.value().equals("scm.pull-request.core"))
            content.put("immutableIdentity", identity);
        ObjectNode capture = mapper.valueToTree(state);
        capture.remove(List.of("facts", "completeness", "limitations"));
        content.set("state", capture);
        var records = content.putObject("files");
        new TreeMap<>(files).forEach((path, bytes) -> {
            // Extracted citations repeat their source record. Commit words are addressed by the Git pair in metadata.
            if (path.startsWith("context/citable-records/") || path.equals("context/commits.json")) return;
            if (path.endsWith(".json")) {
                var record = mapper.readTree(bytes);
                // This timestamp belongs to the mirror observation, not to a provider check run.
                if (kind.value().equals("scm.pull-request.checks")
                        && record.path("head_check_runs") instanceof ObjectNode checks) checks.remove("observed_at");
                records.set(path, canonical(record, true));
            } else records.put(path, digest(bytes));
        });
        return digest(content.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static @Nullable String of(ObjectMapper mapper, SourceKind kind, CaptureContribution contribution) {
        if (!contribution.filesOnDisk().isEmpty() || !contribution.directories().isEmpty())
            throw new IllegalArgumentException("A current record fingerprint cannot stage a checkout");
        if (contribution.files().isEmpty()
                && contribution.citableTexts().isEmpty()
                && !contribution.stateOverrides().containsKey(kind)
                && !contribution.contentStates().containsKey(kind)
                && !contribution.completeness().containsKey(kind)
                && !contribution.immutableIdentities().containsKey(kind)) return null;
        return of(mapper, kind, contribution.stateFor(kind, Instant.EPOCH), contribution.files());
    }

    public static boolean contentAddressed(SourceKind kind) {
        return kind.value().equals("scm.pull-request.diff") || kind.value().equals("scm.repository.tree");
    }

    public static String git(SourceKind kind, String identity) {
        return digest((RENDERER_VERSION + "\0" + kind.value() + "\0" + identity).getBytes(StandardCharsets.UTF_8));
    }

    /** Ref names and object ids bind every reachable object without reading its contents. */
    public static String refs(Map<String, String> refs) {
        var sorted = JsonNodeFactory.instance.objectNode();
        new TreeMap<>(refs).forEach(sorted::put);
        return digest(sorted.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static JsonNode canonical(JsonNode node, boolean record) {
        if (node.isObject()) {
            var result = JsonNodeFactory.instance.objectNode();
            var fields = new TreeMap<String, JsonNode>();
            node.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((key, value) -> {
                // Only the renderer's observations of the mirror are dropped. A person's timestamps remain the work.
                if (!record || !OBSERVATIONS.contains(key)) result.set(key, canonical(value, false));
            });
            return result;
        }
        if (node.isArray()) {
            var result = JsonNodeFactory.instance.arrayNode();
            node.forEach(value -> result.add(canonical(value, record)));
            return result;
        }
        return node;
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}
