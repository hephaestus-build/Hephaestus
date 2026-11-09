package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.evidence.CapturedSource;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceCapture;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureContribution;
import de.tum.cit.aet.hephaestus.evidence.capture.CitableText;
import de.tum.cit.aet.hephaestus.evidence.capture.EvidenceDirectory;
import de.tum.cit.aet.hephaestus.evidence.capture.WorkspaceRefusal;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class JobFolderIndexBuilder {
    private final ClasspathSourceCatalogRegistry catalogs;
    private final ObjectMapper mapper;
    private final Clock clock;

    public JobFolderIndexBuilder(ClasspathSourceCatalogRegistry catalogs, ObjectMapper mapper, Clock clock) {
        this.catalogs = catalogs;
        this.mapper = mapper;
        this.clock = clock;
    }

    public PreparedEvidence build(ItemKind kind, Map<SourceKind, CaptureContribution> contributions) {
        var memory = new LinkedHashMap<String, byte[]>();
        var disk = new LinkedHashMap<String, Path>();
        var cleanups = new ArrayList<AutoCloseable>();
        var directories = new ArrayList<EvidenceDirectory>();
        var refusals = new ArrayList<WorkspaceRefusal>();
        var texts = new ArrayList<CitableText>();
        var sources = new ArrayList<SourceCapture>();
        var files = new ArrayList<FolderSource>();
        var now = clock.instant();
        contributions.forEach((source, contribution) -> {
            var capturedFiles = new ArrayList<CapturedSource>();
            contribution.files().forEach((path, bytes) -> {
                if (memory.putIfAbsent(path, bytes.clone()) != null || disk.containsKey(path)) throw duplicate(path);
                capturedFiles.add(
                        new CapturedSource(path, mediaType(path), ProvenanceDigest.sha256Hex(bytes), bytes.length));
            });
            contribution.filesOnDisk().forEach((path, original) -> {
                if (disk.putIfAbsent(path, original) != null || memory.containsKey(path)) throw duplicate(path);
                try (var bytes = Files.newInputStream(original)) {
                    capturedFiles.add(new CapturedSource(
                            path, mediaType(path), ProvenanceDigest.sha256Hex(bytes), Files.size(original)));
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            });
            contribution.citableTexts().forEach(text -> {
                if (!text.source().equals(source.value()))
                    throw new IllegalArgumentException("A capture registered text under another source");
                if (contribution.files().containsKey(text.stagedPath())
                        || contribution.filesOnDisk().containsKey(text.stagedPath())) {
                    texts.add(text);
                } else {
                    var roots = contribution.directories().stream()
                            .filter(directory -> Files.isRegularFile(
                                    directory
                                            .source()
                                            .resolve(
                                                    text.stagedPath().startsWith(directory.target())
                                                            ? text.stagedPath()
                                                                    .substring(directory
                                                                            .target()
                                                                            .length())
                                                            : text.stagedPath()),
                                    LinkOption.NOFOLLOW_LINKS))
                            .toList();
                    if (roots.size() != 1)
                        throw new IllegalArgumentException("A citable text has no unique captured directory");
                    var root = roots.getFirst();
                    texts.add(new CitableText(
                            text.source(),
                            text.path(),
                            text.side(),
                            text.commit(),
                            text.stagedPath().startsWith(root.target())
                                    ? text.stagedPath()
                                    : root.target() + text.stagedPath(),
                            text.sha256()));
                }
            });
            directories.addAll(contribution.directories());
            refusals.addAll(contribution.refusals());
            var cleanup = contribution.cleanup();
            if (cleanup != null) cleanups.add(cleanup);
            var state = contribution.stateFor(source, now);
            sources.add(new SourceCapture(
                    source,
                    state,
                    catalogs.current()
                            .source(source)
                            .orElseThrow(() -> new IllegalArgumentException("Unknown captured source: " + source))
                            .citation(),
                    capturedFiles));
            capturedFiles.forEach(file -> files.add(new FolderSource(source, file)));
        });
        var manifest = new JobFolderIndex(
                catalogs.current().version(),
                catalogs.catalogDigest(),
                kind.value(),
                now,
                sources,
                refusals,
                files,
                texts);
        memory.put(SandboxLayout.MANIFEST_PATH, mapper.writeValueAsBytes(manifest));
        return new PreparedEvidence(memory, disk, cleanups, manifest, directories);
    }

    private static IllegalStateException duplicate(String path) {
        return new IllegalStateException("Duplicate captured path: " + path);
    }

    private static String mediaType(String path) {
        return path.endsWith(".json") ? "application/json" : "text/plain";
    }
}
