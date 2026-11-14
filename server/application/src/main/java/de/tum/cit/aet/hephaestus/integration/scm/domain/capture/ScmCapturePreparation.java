package de.tum.cit.aet.hephaestus.integration.scm.domain.capture;

import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

// Share one fetch and its availability fact across the sources in this capture.
public final class ScmCapturePreparation {
    private boolean fetched;
    private ReviewRepositoryPreparer.@Nullable PreparedReview prepared;
    private @Nullable RuntimeException failure;
    private @Nullable SourceAbsenceReason unavailable;

    /**
     * A preparation that reads the mirror as it is, with no fetch. Commits are content-addressed, so a range that the
     * mirror holds reads as a fetch would read it; a missing commit makes the clone unavailable.
     */
    public static ScmCapturePreparation fromMirror() {
        var preparation = new ScmCapturePreparation();
        preparation.fetched = true;
        return preparation;
    }

    /**
     * The review prepared so far, or null when no source has asked yet or the preparation failed.
     */
    public synchronized ReviewRepositoryPreparer.@Nullable PreparedReview prepared() {
        return prepared;
    }

    public synchronized @Nullable SourceAbsenceReason unavailableReason() {
        return unavailable;
    }

    /**
     * Runs the capture's fetch, before its transaction. What it cannot fetch is what {@link #prepare} answers for: a
     * source that never reads the clone is not refused for it.
     */
    public synchronized void fetch(Runnable fetch) {
        fetched = true;
        settle(() -> {
            fetch.run();
            return null;
        });
    }

    public synchronized ReviewRepositoryPreparer.@Nullable PreparedReview prepare(
            ReviewRepositoryPreparer preparer, ScmCaptureContext capture) {
        if (prepared == null && failure == null && unavailable == null) {
            if (!fetched) throw new IllegalStateException("A capture reads the clone only after its fetch");
            prepared = settle(() -> preparer.prepare(capture));
        }
        if (failure != null) throw failure;
        return prepared;
    }

    private ReviewRepositoryPreparer.@Nullable PreparedReview settle(
            Supplier<ReviewRepositoryPreparer.@Nullable PreparedReview> step) {
        try {
            return step.get();
        } catch (GitRepositoryManager.RepositoryFetchException exception) {
            unavailable = SourceAbsenceReason.REPOSITORY_FETCH_FAILED;
        } catch (ItemCaptureException exception) {
            if (exception.code() == ItemCaptureCode.VERSION_UNAVAILABLE)
                unavailable = SourceAbsenceReason.REVIEW_VERSION_UNAVAILABLE;
            else failure = exception;
        } catch (RuntimeException exception) {
            failure = exception;
        }
        return null;
    }
}
