package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.agent.context.providers.ReviewRepositoryPreparer;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import org.jspecify.annotations.Nullable;

/**
 * One Git preparation per review request. Every source that reads the clone asks here, so the fetch
 * runs once, and a failure is handed to each of them in turn so each still records it for its own
 * source.
 */
public final class ReviewPreparation {
    private ReviewRepositoryPreparer.@Nullable PreparedReview prepared;
    private @Nullable RuntimeException failure;

    public synchronized ReviewRepositoryPreparer.PreparedReview prepare(
            ReviewRepositoryPreparer preparer, AgentJob job) {
        if (prepared != null) return prepared;
        if (failure != null) throw failure;
        try {
            var review = preparer.prepare(job);
            prepared = review;
            return review;
        } catch (RuntimeException exception) {
            failure = exception;
            throw exception;
        }
    }
}
