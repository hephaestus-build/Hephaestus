package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.evidence.CapturedSource;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceFingerprint;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/** The fingerprint of every source that the captured session could read. */
public final class CapturedSourceFingerprints {
    private static final String CHECKOUTS = "repos/";

    private CapturedSourceFingerprints() {}

    /** The content dependencies of every source that the session could read. */
    public static Map<String, String> of(ObjectMapper mapper, PreparedEvidence evidence) {
        var manifest = Objects.requireNonNull(evidence.manifest());
        var fingerprints = new HashMap<String, String>();
        for (var source : manifest.sources()) {
            if (!(source.state() instanceof SourceCaptureState.Available)) continue;
            var files = new HashMap<String, byte[]>();
            for (var file :
                    SourceFingerprint.contentAddressed(source.kind()) ? List.<CapturedSource>of() : source.files()) {
                if (file.path().startsWith(CHECKOUTS)) continue;
                var content = bytes(evidence, file);
                if (content == null) throw new IllegalStateException("A captured source file is unavailable");
                files.put(file.path(), content);
            }
            var fingerprint = SourceFingerprint.of(mapper, source.kind(), source.state(), files);
            if (fingerprint != null) fingerprints.put(source.kind().value(), fingerprint);
        }
        return Map.copyOf(fingerprints);
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
}
