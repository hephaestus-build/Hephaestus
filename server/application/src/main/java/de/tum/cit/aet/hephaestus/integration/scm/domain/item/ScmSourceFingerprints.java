package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceFingerprint;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureRequest;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemFingerprintReader;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryRefState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.PullRequestContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.RepositoryTreeContentSource;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Git freshness reads the shared source observation; record freshness uses the capture renderer. */
@Component
@ConditionalOnCaptureRole
@RequiredArgsConstructor
public class ScmSourceFingerprints implements ItemFingerprintReader {
    private final ScmItemService source;
    private final ScmItemAccess items;
    private final RepositoryRefState refs;

    @Override
    public boolean supports(ItemRef item) {
        return source.supports(item);
    }

    @Override
    @Transactional(readOnly = true, noRollbackFor = ItemCaptureException.class)
    public Map<SourceKind, String> fingerprints(ItemRef item, Set<SourceKind> kinds, ObjectMapper mapper) {
        if (!item.kind().equals(ScmItemEvents.PULL_REQUEST)
                && !item.kind().equals(ScmItemEvents.ISSUE)
                && !item.kind().equals(ScmItemEvents.PULL_REQUEST_REVIEW)) return Map.of();
        var current = source.current(item);
        if (current.isEmpty()) return Map.of();
        var snapshot = current.orElseThrow();
        var fingerprints = new HashMap<SourceKind, String>();
        for (var kind : kinds) {
            if (SourceFingerprint.contentAddressed(kind)) {
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
            } else {
                var contribution = source.capture(
                        snapshot,
                        new CaptureRequest(
                                new UUID(0, 0),
                                new UUID(0, 0),
                                0,
                                Set.of(kind),
                                ClasspathSourceCatalogRegistry.CURRENT_VERSION_VALUE,
                                3));
                var cleanup = contribution.cleanup();
                try (cleanup) {
                    var fingerprint = SourceFingerprint.of(mapper, kind, contribution);
                    if (fingerprint != null) fingerprints.put(kind, fingerprint);
                } catch (Exception failure) {
                    throw new IllegalStateException("Could not release the current source read", failure);
                }
            }
        }
        return Map.copyOf(fingerprints);
    }
}
