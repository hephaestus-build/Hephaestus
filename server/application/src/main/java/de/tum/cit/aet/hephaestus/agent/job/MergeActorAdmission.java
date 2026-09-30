package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.PracticeBinding;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import java.util.List;

/**
 * Whether a GitLab merge's review has to wait for Hephaestus to know who merged it. A practice about merging is judged
 * only when the author merged ({@code PracticeCatalogInjector#subjectNameable}), so a review started without the
 * merger would pass over that practice as if it did not apply. GitLab's merge hook often names no merger, and the read
 * after it or a sync supplies one; GitHub's names the merger itself, and its merges keep their existing path. The live
 * listener and the pending-signal resubmitter ask the same question of the merge request they just loaded, so a merger
 * recorded meanwhile lets the review run with it.
 */
final class MergeActorAdmission {

    private MergeActorAdmission() {}

    /**
     * Whether a review of GitLab merge request {@code pr} for {@code signal} would judge one of
     * {@code matchedPractices} as the merger's conduct while the merger is unknown.
     */
    static boolean awaitsMerger(PullRequest pr, SignalName signal, List<Practice> matchedPractices) {
        return signal.equals(ScmSignals.PULL_REQUEST_MERGED)
                && pr.getProvider() instanceof IdentityProvider provider
                && provider.getType() == IdentityProviderType.GITLAB
                && pr.getMergedBy() == null
                && matchedPractices.stream()
                        .anyMatch(practice ->
                                PracticeBinding.subjectRoleOf(practice.getBindings(), signal) == ActorRole.MERGER);
    }
}
