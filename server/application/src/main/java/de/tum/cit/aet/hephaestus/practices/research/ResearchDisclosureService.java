package de.tum.cit.aet.hephaestus.practices.research;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.release.RunningRelease;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.PracticeReviewDigest;
import de.tum.cit.aet.hephaestus.practices.curated.BundledPracticeCatalogLoader;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds and releases one research dataset synchronously: snapshot, assemble, authorise, return bytes.
 * Nothing is stored except the disclosure receipt and its protected subject mapping, and no
 * transaction is open while the caller sends the bytes.
 *
 * <p>This service does not authenticate. Its only callers are the instance-admin controllers, which
 * check the role, refuse delegated contexts and apply the deployment's assurance policy before calling in.
 * Fresh passkey verification is required by default.
 */
@Service
@ConditionalOnServerRole
@WorkspaceAgnostic("Research datasets belong to the instance admin")
public class ResearchDisclosureService {

    static final int MAX_WINDOW_DAYS = 400;
    /** The bundled catalogue's location in the public source tree, resolvable at the exporting release. */
    static final String CATALOG_RESOURCE = "server/application/src/main/resources/practices/default-catalog.json";

    private final ResearchDatasetSource source;
    private final ResearchDisclosureRecorder recorder;
    private final BundledPracticeCatalogLoader bundled;
    private final PracticeReviewDigest digest;
    private final RunningRelease release;
    private final JsonMapper mapper;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private volatile @Nullable Map<String, ResearchDatasetAssembler.BundledStandard> bundledStandards;

    public ResearchDisclosureService(
            ResearchDatasetSource source,
            ResearchDisclosureRecorder recorder,
            BundledPracticeCatalogLoader bundled,
            PracticeReviewDigest digest,
            RunningRelease release,
            JsonMapper mapper,
            Clock clock) {
        this.source = source;
        this.recorder = recorder;
        this.bundled = bundled;
        this.digest = digest;
        this.release = release;
        this.mapper = mapper;
        this.clock = clock;
    }

    public static final class ReleasedDataset {
        private final UUID datasetId;
        private final byte[] content;

        public ReleasedDataset(UUID datasetId, byte[] content) {
            this.datasetId = datasetId;
            this.content = content;
        }

        public UUID datasetId() {
            return datasetId;
        }

        public byte[] content() {
            return content;
        }
    }

    /**
     * @param firstDay first UTC day of the window
     * @param lastDay last UTC day of the window, inclusive
     * @param useUntil end of permitted use, as the study protocol sets it; must lie in the future
     */
    public ReleasedDataset release(
            long workspaceId, LocalDate firstDay, LocalDate lastDay, Instant useUntil, long adminId) {
        Instant now = clock.instant();
        requireWindow(firstDay, lastDay);
        requireFutureUse(useUntil, now);
        Instant from = firstDay.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = lastDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        ResearchSnapshot snapshot = source.read(workspaceId, from, to);
        UUID datasetId = UUID.randomUUID();
        var assembled = ResearchDatasetAssembler.assemble(
                snapshot,
                datasetId,
                from,
                to,
                useUntil,
                bundledStandards(),
                standardSource(),
                ResearchPseudonyms.fresh(random));
        byte[] content = mapper.writeValueAsBytes(assembled.dataset());
        recorder.authorize(
                new ResearchDisclosureRecorder.Release(
                        datasetId,
                        workspaceId,
                        from,
                        to,
                        useUntil,
                        sha256(content),
                        assembled.dataset().counts()),
                snapshot,
                assembled.participantKeys(),
                adminId,
                clock.instant());
        return new ReleasedDataset(datasetId, content);
    }

    static void requireWindow(LocalDate firstDay, LocalDate lastDay) {
        if (lastDay.isBefore(firstDay) || firstDay.plusDays(MAX_WINDOW_DAYS).isBefore(lastDay.plusDays(1))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Choose a window of 1 to " + MAX_WINDOW_DAYS + " days.");
        }
    }

    /** Any future date the study protocol names. No maximum was selected, so none is imposed. */
    static void requireFutureUse(Instant useUntil, Instant now) {
        if (!useUntil.isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a use-until date in the future.");
        }
    }

    public List<ResearchDisclosureRecorder.OpenDisclosure> removalManifest() {
        return recorder.detectRemovals(clock.instant());
    }

    public int recordRemoved(UUID datasetId, List<String> participantKeys, long adminId) {
        if (participantKeys.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name at least one participant key.");
        }
        return recorder.recordRemoved(datasetId, participantKeys, adminId, clock.instant());
    }

    public void close(UUID datasetId, ResearchDisclosure.Closure closure, long adminId) {
        recorder.close(datasetId, closure, adminId, clock.instant());
    }

    /**
     * Slug → review digest and criteria of the definition this release ships. Read from the bundled
     * catalogue only, never from the effective catalogue, which instance overrides can change. Scripts
     * are not taken.
     */
    Map<String, ResearchDatasetAssembler.BundledStandard> bundledStandards() {
        Map<String, ResearchDatasetAssembler.BundledStandard> known = bundledStandards;
        if (known == null) {
            Map<String, ResearchDatasetAssembler.BundledStandard> computed = new LinkedHashMap<>();
            for (var entry : bundled.catalog().practices()) {
                var definition = entry.definition().definition();
                computed.put(
                        entry.slug(),
                        new ResearchDatasetAssembler.BundledStandard(digest.of(definition), definition.criteria()));
            }
            known = Map.copyOf(computed);
            bundledStandards = known;
        }
        return known;
    }

    ResearchDataset.StandardSource standardSource() {
        var running = release.get();
        return new ResearchDataset.StandardSource(
                CATALOG_RESOURCE, running.version(), running.channel().name(), running.commit());
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
