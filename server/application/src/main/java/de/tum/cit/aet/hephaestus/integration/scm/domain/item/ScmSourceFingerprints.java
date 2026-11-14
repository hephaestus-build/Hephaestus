package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceFingerprint;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureContribution;
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
            var read = occasion != null && holds(item, snapshot, occasion) ? occasion : null;
            fingerprints.putAll(
                    ItemSource.recordFingerprints(this::captureFromMirror, snapshot, records, read, mapper));
        }
        return Map.copyOf(fingerprints);
    }

    /**
     * A capture from the synced mirror. A source that reads the clone answers without the reviewed commits when the
     * mirror lacks them, for example with only the references that the title names. That answer is not what the
     * review's capture read, so the source counts as unreadable: the comparison is unverifiable, never a change.
     */
    private CaptureContribution captureFromMirror(ItemSnapshot pinned, CaptureRequest request) {
        var mirror = ScmCapturePreparation.fromMirror();
        var contribution = source.capture(pinned, request, mirror);
        var unread = mirror.unavailableReason();
        if (unread == null) return contribution;
        var cleanup = contribution.cleanup();
        try (cleanup) {
            return CaptureContribution.unavailable(request.sources(), unread);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not release the current source read", failure);
        }
    }

    /**
     * Whether the work is still at the version of {@code occasion}. A capture reads an occasion only at its own
     * version; once a newer version replaced it, a capture now reads the current work, which shows the change. An
     * occasion whose record expired cannot be read again, so the comparison is unverifiable, never a change.
     */
    private boolean holds(ItemRef item, ItemSnapshot current, UUID occasion) {
        try {
            source.pinOccasion(item, current.version(), occasion);
            return true;
        } catch (ItemCaptureException replaced) {
            if (replaced.code() != ItemCaptureCode.VERSION_UNAVAILABLE) throw replaced;
            return false;
        }
    }
}
