package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceFingerprint;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureRequest;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemFingerprintReader;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryRefState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.PullRequestContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.RepositoryTreeContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ScmCapturePreparation;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Git freshness reads the shared source observation; record freshness uses the capture renderer on the synced mirror.
 * Neither fetches from the provider.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ScmSourceFingerprints implements ItemFingerprintReader {
    private final ScmItemService source;
    private final ScmItemAccess items;
    private final RepositoryRefState refs;
    private final ScmWorkCapture capture;
    private final ScmItemTransactions transactions;

    @Override
    public boolean supports(ItemRef item) {
        return source.supports(item);
    }

    /**
     * Not transactional: each record read runs in its own short transaction, or in the caller's. It reads only the
     * mirror, so a caller may hold row locks.
     */
    @Override
    public Map<SourceKind, String> fingerprints(
            ItemRef item, Set<SourceKind> kinds, @Nullable UUID occasion, ObjectMapper mapper) {
        if (!item.kind().equals(ScmItemEvents.PULL_REQUEST)
                && !item.kind().equals(ScmItemEvents.ISSUE)
                && !item.kind().equals(ScmItemEvents.PULL_REQUEST_REVIEW)) return Map.of();
        var current = source.current(item);
        if (current.isEmpty()) return Map.of();
        var snapshot = current.orElseThrow();
        var fingerprints = new HashMap<SourceKind, String>();
        var records = new HashSet<SourceKind>();
        for (var kind : kinds) {
            if (!SourceFingerprint.contentAddressed(kind)) {
                records.add(kind);
                continue;
            }
            var version = ScmVersion.parse(snapshot.version());
            if (version.isEmpty()) continue;
            var pair = version.orElseThrow();
            if (kind.equals(PullRequestContentSource.DIFF))
                fingerprints.put(kind, SourceFingerprint.git(kind, pair.base() + ":" + pair.head()));
            else if (kind.equals(RepositoryTreeContentSource.KIND)) {
                var repository = items.parent(item, ScmItemEvents.REPOSITORY);
                refs.current(item.workspaceId(), Objects.requireNonNull(repository.sourceId()))
                        .ifPresent(value ->
                                fingerprints.put(kind, SourceFingerprint.git(kind, pair.head() + ":" + value)));
            }
        }
        if (!records.isEmpty()) {
            var held = occasion == null ? null : held(item, snapshot, occasion);
            fingerprints.putAll(ItemSource.recordFingerprints(
                    (pinned, request) -> source.capture(pinned, request, ScmCapturePreparation.fromMirror()),
                    held != null ? held : snapshot,
                    records,
                    held != null ? occasion : null,
                    mapper));
        }
        return Map.copyOf(fingerprints);
    }

    /**
     * Fetches the reviewed repository when a record source reads the clone, as a capture does before its transaction.
     * Another server may have captured the result, or this server may have evicted its mirror.
     */
    @Override
    public void sync(ItemRef item, Set<SourceKind> kinds, @Nullable UUID occasion) {
        if (!ScmWorkCapture.retains(item.kind())) return;
        var records = kinds.stream()
                .filter(kind -> !SourceFingerprint.contentAddressed(kind))
                .collect(Collectors.toSet());
        if (records.isEmpty()) return;
        var current = source.current(item);
        if (current.isEmpty()) return;
        var snapshot = current.orElseThrow();
        var held = occasion == null ? null : held(item, snapshot, occasion);
        var read = held != null ? occasion : null;
        var request = new CaptureRequest(
                new UUID(0, 0),
                new UUID(0, 0),
                0,
                records,
                ClasspathSourceCatalogRegistry.CURRENT_VERSION_VALUE,
                3,
                null,
                records,
                read);
        if (!capture.readsClone(item.kind(), request)) return;
        var pinned = held != null ? held : snapshot;
        capture.fetch(transactions.read(() -> capture.reviewedRepository(pinned, read)));
    }

    /**
     * The occasion's own snapshot while the work is still at its version, else null. A capture reads an occasion only
     * at its own version; once a newer version replaced it, a capture now reads the current work, which shows the
     * change. An occasion whose record expired cannot be read again, so the comparison is unverifiable, never a change.
     * The occasion is read under its own snapshot, never the current one: its roles are the ones it retained, and a role
     * change outside the version, such as an assignee added after the work opened, is not a change of its sources.
     */
    private @Nullable ItemSnapshot held(ItemRef item, ItemSnapshot current, UUID occasion) {
        try {
            return source.pinOccasion(item, current.version(), occasion);
        } catch (ItemCaptureException replaced) {
            if (replaced.code() != ItemCaptureCode.VERSION_UNAVAILABLE) throw replaced;
            return null;
        }
    }
}
