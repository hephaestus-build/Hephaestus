package de.tum.cit.aet.hephaestus.integration.core.fabric;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Garbage-collects the Context-Fabric cache (ADR 0020). Disk is a rebuildable cache, so collection is
 * best-effort: it first prunes per-job replay directories older than the retention window, then sweeps
 * blobs that are both unreferenced and older than that same window. The age barrier protects captures
 * that have written a blob but have not yet published their manifest.
 */
@ConditionalOnServerRole
@Component
@WorkspaceAgnostic(
        "Content-addressed blobs and job replay directories are shared storage regions, so retention runs globally.")
public class FabricGarbageCollector {

    private static final Logger log = LoggerFactory.getLogger(FabricGarbageCollector.class);

    private final FabricLayout layout;
    private final ContentAddressedStore cas;
    private final JsonMapper objectMapper;
    private final Duration retention;

    public FabricGarbageCollector(
            FabricLayout layout,
            ContentAddressedStore cas,
            JsonMapper objectMapper,
            @Value("${hephaestus.fabric.gc-retention-days:30}") long retentionDays) {
        if (retentionDays < 1) {
            throw new IllegalArgumentException("hephaestus.fabric.gc-retention-days must be positive");
        }
        this.layout = layout;
        this.cas = cas;
        this.objectMapper = objectMapper;
        this.retention = Duration.ofDays(retentionDays);
    }

    @Scheduled(
            fixedRateString = "${hephaestus.fabric.gc-rate:86400000}",
            initialDelayString = "${hephaestus.fabric.gc-initial-delay:3600000}")
    @SchedulerLock(name = "context-fabric-garbage-collection", lockAtMostFor = "PT1H", lockAtLeastFor = "PT30S")
    public void collect() {
        Instant cutoff = Instant.now().minus(retention);
        int prunedJobs = pruneExpiredJobs(cutoff);
        ReferenceScan references = scanReferences();
        int sweptBlobs = references.complete() ? cas.sweep(references.shas(), cutoff) : 0;
        if (prunedJobs > 0 || sweptBlobs > 0) {
            log.info("Fabric GC: pruned {} expired job dir(s), swept {} orphaned CAS blob(s)", prunedJobs, sweptBlobs);
        }
    }

    int pruneExpiredJobs(Instant cutoff) {
        Path jobsRoot = layout.jobsRoot();
        if (!Files.isDirectory(jobsRoot)) {
            return 0;
        }
        int pruned = 0;
        try (Stream<Path> jobDirs = Files.list(jobsRoot)) {
            for (Path jobDir : jobDirs.filter(Files::isDirectory).toList()) {
                try {
                    if (Files.getLastModifiedTime(jobDir).toInstant().isBefore(cutoff)) {
                        deleteRecursively(jobDir);
                        pruned++;
                    }
                } catch (IOException e) {
                    log.warn("Fabric GC could not prune {}: {}", jobDir, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Fabric GC could not list jobs root {}: {}", jobsRoot, e.getMessage());
        }
        return pruned;
    }

    Set<String> referencedShas() {
        return scanReferences().shas();
    }

    private ReferenceScan scanReferences() {
        Set<String> shas = new HashSet<>();
        boolean[] complete = {true};
        Path jobsRoot = layout.jobsRoot();
        if (!Files.isDirectory(jobsRoot)) {
            return new ReferenceScan(shas, true);
        }
        try (Stream<Path> manifests = Files.walk(jobsRoot)) {
            manifests.filter(FabricGarbageCollector::isManifest).forEach(manifest -> {
                try {
                    JsonNode root = objectMapper.readTree(Files.readAllBytes(manifest));
                    for (JsonNode file : root.path("files")) {
                        addSha(shas, file.path("sha256"));
                    }
                    for (JsonNode entry : root.path("entries")) {
                        addSha(shas, entry.path("sha256"));
                    }
                    for (JsonNode source : root.path("sources")) {
                        for (JsonNode artifact : source.path("artifacts")) {
                            addSha(shas, artifact.path("sha256"));
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    complete[0] = false;
                    log.warn("Fabric GC skipped blob sweep because manifest {} is unreadable", manifest);
                }
            });
        } catch (IOException e) {
            log.warn("Fabric GC could not walk jobs root {}: {}", jobsRoot, e.getMessage());
            complete[0] = false;
        }
        return new ReferenceScan(Set.copyOf(shas), complete[0]);
    }

    private record ReferenceScan(Set<String> shas, boolean complete) {}

    private static boolean isManifest(Path path) {
        String name = path.getFileName().toString();
        return name.equals("artifact-source-manifest.json")
                || name.equals("execution-inputs.json")
                || name.equals("execution-outputs.json")
                || (name.startsWith("execution-proxy-") && name.endsWith(".json"));
    }

    private static void addSha(Set<String> shas, JsonNode value) {
        String sha = value.asString("");
        if (!sha.isBlank()) {
            shas.add(sha);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
