package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.framework.IntegrationManifestRegistry;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.PracticeBinding;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import java.util.List;

/**
 * Whether a merge's review has to wait for Hephaestus to know who merged it. A practice about merging is judged only
 * when the author merged ({@code PracticeCatalogInjector#subjectNameable}), so a review started without the merger
 * would pass over that practice as if it did not apply. Only an integration that declares it may name the merger after
 * the merge ({@code IntegrationManifest#rolesNamedAfterTheOccasion}) holds its merges; any other keeps its existing
 * path. The live listener and the pending-signal resubmitter ask the same question of the merge request they just
 * loaded, so a merger recorded meanwhile lets the review run with it.
 */
final class MergeActorAdmission {

    private MergeActorAdmission() {}

    /**
     * Whether a review of {@code pr} for {@code signal} would judge one of {@code matchedPractices} as the merger's
     * conduct while a merger its integration names only after the merge is still unknown.
     */
    static boolean awaitsMerger(
            IntegrationManifestRegistry manifests, PullRequest pr, SignalName signal, List<Practice> matchedPractices) {
        return signal.equals(ScmSignals.PULL_REQUEST_MERGED)
                && pr.getMergedBy() == null
                && pr.getProvider() instanceof IdentityProvider provider
                && manifests
                        .manifestFor(provider.kind())
                        .filter(manifest ->
                                manifest.rolesNamedAfterTheOccasion().contains(ActorRole.MERGER))
                        .isPresent()
                && matchedPractices.stream()
                        .anyMatch(practice ->
                                PracticeBinding.subjectRoleOf(practice.getBindings(), signal) == ActorRole.MERGER);
    }
}
