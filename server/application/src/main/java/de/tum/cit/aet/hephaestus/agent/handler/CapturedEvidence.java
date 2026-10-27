package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndex;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.evidence.SourceCapture;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.capture.CitableText;
import de.tum.cit.aet.hephaestus.integration.core.spi.CandidateRefusal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/** The canonical captured-file inventory; domain version strings remain opaque. */
final class CapturedEvidence {
    record FileProof(SourceKind kind, String sha256) {}

    private final JobFolderIndex manifest;
    private final Set<SourceKind> available;
    private final Map<String, FileProof> files;

    private CapturedEvidence(JobFolderIndex manifest) {
        this.manifest = manifest;
        var sources = new HashSet<SourceKind>();
        manifest.sources().forEach(source -> {
            if (source.state() instanceof SourceCaptureState.Available) sources.add(source.kind());
        });
        var indexed = new HashMap<String, FileProof>();
        for (var entry : manifest.files()) {
            if (indexed.put(
                            entry.file().path(),
                            new FileProof(entry.kind(), entry.file().sha256()))
                    != null) {
                throw new JobDeliveryException("A captured path belongs to multiple sources");
            }
        }
        available = Set.copyOf(sources);
        files = Map.copyOf(indexed);
    }

    static CapturedEvidence of(JobFolderIndex manifest) {
        return new CapturedEvidence(manifest);
    }

    static CapturedEvidence of(ReviewJob job, ObjectMapper mapper) {
        var capture = job.getCapture();
        if (capture == null || !capture.path("manifest").isObject()) {
            throw new JobDeliveryException("The attempt has no captured source manifest");
        }
        return of(mapper.treeToValue(capture.get("manifest"), JobFolderIndex.class));
    }

    SourceContractVersion contractVersion() {
        return manifest.contractVersion();
    }

    Set<SourceKind> availableSources() {
        return available;
    }

    /** Every source this attempt captured or tried to capture, whatever came of it. */
    Set<SourceKind> listedSources() {
        return manifest.sources().stream().map(SourceCapture::kind).collect(Collectors.toSet());
    }

    /**
     * Whether {@code kind} was captured whole at {@code version}. An identity names the version, or begins with it and
     * a colon: the repository tree's identity is {@code <commit>:<tree>}. A partial capture leaves files out, so no
     * search of it can show that the source lacks something.
     */
    boolean completeAt(SourceKind kind, String version) {
        return manifest.sources().stream().anyMatch(source -> {
            if (!source.kind().equals(kind)
                    || !(source.state() instanceof SourceCaptureState.Available available)
                    || available.completeness() != SourceCompleteness.COMPLETE) return false;
            String identity = available.facts().immutableIdentity();
            return identity != null && (identity.equals(version) || identity.startsWith(version + ":"));
        });
    }

    Map<String, FileProof> files() {
        return files;
    }

    @Nullable
    FileProof file(String path) {
        return files.get(path);
    }

    CitableText resolve(SourceKind kind, String path, CitationSide side, @Nullable String commit) {
        var matches = manifest.citableTexts().stream()
                .filter(text -> text.source().equals(kind.value())
                        && text.path().equals(path)
                        && text.side() == side
                        && Objects.equals(text.commit(), commit))
                .toList();
        if (!available.contains(kind) || matches.size() != 1) {
            throw new CandidateEvidenceVerifier.Unverified(
                    CandidateRefusal.SOURCE_UNAVAILABLE, "The citation has no unique captured text", null);
        }
        return matches.getFirst();
    }

    /**
     * The source file that a verified citation of {@code proof} names. {@link #requireFile} checks only the file's kind,
     * and a file of that kind can hold another captured text. Such a file is replaced by the text's own record, or else by
     * its staged copy, so the stored citation names the bytes its quote was verified in. A witness that holds no other
     * text, such as the checkout HEAD, stays.
     */
    String witness(String sourcePath, CitableText proof) {
        // ScmCitableTexts stages a string extracted from a record under context/citable-records/ and names it
        // "<record>#/<pointer>"; any other path, a file name with "#" included, is its own record.
        int fragment = proof.path().indexOf("#/");
        String record = fragment >= 0 && proof.stagedPath().startsWith("context/citable-records/")
                ? proof.path().substring(0, fragment)
                : proof.path();
        if (sourcePath.equals(record) || sourcePath.equals(proof.stagedPath())) return sourcePath;
        boolean holdsAnotherText = manifest.citableTexts().stream()
                .anyMatch(text -> text.source().equals(proof.source())
                        && text.stagedPath().equals(sourcePath));
        if (!holdsAnotherText) return sourcePath;
        for (String own : new String[] {record, proof.stagedPath()}) {
            var file = files.get(own);
            if (file != null && file.kind().value().equals(proof.source())) return own;
        }
        return sourcePath;
    }

    FileProof requireFile(SourceKind kind, String path) {
        var file = files.get(path);
        if (!available.contains(kind) || file == null || !file.kind().equals(kind)) {
            throw new JobDeliveryException("The cited source was not captured at this path");
        }
        return file;
    }
}
