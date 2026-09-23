package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.agent.context.providers.ReviewRepositoryPreparer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The reviewed change as planning reads it: the paths it touches and its text. Nothing here is
 * staged for the review — the container derives its own view of the change from the checkout; the
 * server reads the change only to decide which practices have their declared subject in it.
 */
public interface ReviewChange {

    /** Every path the change touches, under its old name and its new one. */
    Set<String> changedPaths();

    /** The change as {@code git diff} prints it. */
    String text();

    /**
     * The change of a request whose repository was prepared, read from the mirror once per aspect and
     * only when a practice's declared subject asks; null when no source prepared one.
     */
    static @Nullable ReviewChange of(GitRepositoryManager git, ContextRequest request) {
        if (!(request instanceof ContextRequest.PracticeReviewRequest practiceReview)) return null;
        var review = practiceReview.preparation().prepared();
        return review == null ? null : new Mirrored(git, review);
    }

    final class Mirrored implements ReviewChange {
        private final GitRepositoryManager git;
        private final ReviewRepositoryPreparer.PreparedReview review;
        private @Nullable Set<String> paths;
        private @Nullable String text;

        private Mirrored(GitRepositoryManager git, ReviewRepositoryPreparer.PreparedReview review) {
            this.git = git;
            this.review = review;
        }

        @Override
        public synchronized Set<String> changedPaths() {
            if (paths == null) paths = git.changedPaths(review.key(), review.target(), review.head());
            return paths;
        }

        @Override
        public synchronized String text() {
            if (text == null) text = git.unifiedDiff(review.key(), review.target(), review.head());
            return text;
        }
    }
}
