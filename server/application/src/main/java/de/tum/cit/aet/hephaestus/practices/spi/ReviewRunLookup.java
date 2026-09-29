package de.tum.cit.aet.hephaestus.practices.spi;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.practices.review.TriggerMode;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOutcomeLookup.ReviewRunState;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * What a review run knows about itself, read without knowing about agent-job persistence: the work it reviewed
 * and the facts it records. {@link ReviewRunNarrativeLookup} does the same for what a run wrote about each
 * observation. Both lookups answer by review id, and a run this workspace does not own is absent.
 */
public interface ReviewRunLookup {

    /** The work each run reviewed, named by what the mirror calls it now while the mirror still holds it. */
    Map<UUID, Target> findTargets(long workspaceId, Collection<UUID> jobIds);

    Map<UUID, ReviewRunFacts> findFacts(long workspaceId, Collection<UUID> jobIds);

    record Target(
            @NonNull ArtifactKind type,
            @Nullable Long id,
            @Nullable IntegrationKind provider,
            @Nullable Integer number,
            @NonNull String title,
            @Nullable String repositoryName,
            @Nullable String channelName,
            @Nullable String url) {
        public Target withTitle(String title) {
            return new Target(type, id, provider, number, title, repositoryName, channelName, url);
        }
    }

    /**
     * @param target the work the run reviewed, as {@link #findTargets} names it
     * @param practicesEvaluated how many practices the run measured, or null when it wrote no coverage ledger
     * @param feedbackUrl the run's summary comment on the work's own page, or null when it posted none or its
     *     provider cannot address one
     */
    record ReviewRunFacts(
            Target target,
            ReviewRunState status,
            TriggerMode triggerMode,
            @Nullable Integer practicesEvaluated,
            @Nullable String feedbackUrl) {}
}
