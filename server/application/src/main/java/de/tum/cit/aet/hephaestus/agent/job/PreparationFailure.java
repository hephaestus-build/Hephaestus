package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.context.EvidenceCollectionException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ReviewSourceNotReadyException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/** A preparation operation and a cause classified by type, without sensitive exception text. */
record PreparationFailure(Stage stage, Kind kind, JobPreparationException.Reason reason, List<String> exceptionTypes) {

    /** The owned operations before execution, in the order the executor runs them. */
    enum Stage {
        RUN_SETUP("while initializing review preparation"),
        INPUTS("while preparing the review inputs"),
        CREDENTIAL("while issuing the run credential"),
        SPEC("while building the sandbox specification"),
        PROVENANCE("while recording the input provenance"),
        EXECUTION_FENCE("while marking execution as started");

        private final String phrase;

        Stage(String phrase) {
            this.phrase = phrase;
        }

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    enum Kind {
        SOURCE_NOT_READY("the reviewed source was not ready"),
        INPUT_PREPARATION("the review inputs could not be prepared"),
        SOURCE_CAPTURE("the reviewed source could not be captured"),
        IO("a file or network operation failed"),
        REPOSITORY("a repository operation failed"),
        DATABASE("a database operation failed"),
        UNKNOWN("the cause is not classified");

        private final String phrase;

        Kind(String phrase) {
            this.phrase = phrase;
        }

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    static PreparationFailure of(Stage stage, Throwable failure) {
        Kind kind = failure instanceof JobPreparationException ? Kind.INPUT_PREPARATION : Kind.UNKNOWN;
        var reason = JobPreparationException.Reason.UNCLASSIFIED;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (visited.add(current)) {
            if (current instanceof JobPreparationException preparation
                    && reason == JobPreparationException.Reason.UNCLASSIFIED) reason = preparation.reason();
            if (current instanceof ItemCaptureException capture) {
                kind = Kind.SOURCE_CAPTURE;
                if (reason == JobPreparationException.Reason.UNCLASSIFIED)
                    reason = switch (capture.code()) {
                        case ITEM_REMOVED -> JobPreparationException.Reason.REVIEWED_WORK_UNAVAILABLE;
                        case ITEM_NOT_VISIBLE, CONSENT_WITHHELD ->
                            JobPreparationException.Reason.SOURCE_AUTHORITY_UNAVAILABLE;
                        case VERSION_UNAVAILABLE -> JobPreparationException.Reason.REVIEW_RANGE_UNAVAILABLE;
                        case SOURCE_NOT_READY, CAPTURE_FAILED, OCCASION_EVIDENCE_UNAVAILABLE ->
                            JobPreparationException.Reason.REQUIRED_SOURCE_COLLECTION_FAILED;
                    };
            }
            if (current instanceof ReviewSourceNotReadyException) {
                kind = Kind.SOURCE_NOT_READY;
                break;
            }
            // JGit wraps the file or network error of a checkout or fetch in its own types.
            if (current instanceof GitAPIException || current instanceof JGitInternalException) {
                kind = Kind.REPOSITORY;
                break;
            }
            if (current instanceof IOException || current instanceof UncheckedIOException) {
                kind = Kind.IO;
                break;
            }
            if (current instanceof DataAccessException || current instanceof TransactionException) {
                kind = Kind.DATABASE;
                break;
            }
            // Only preparation owners vouch for their wrapped cause; arbitrary and suppressed causes stay unknown.
            if (!(current instanceof JobPreparationException
                    || current instanceof EvidenceCollectionException
                    || current instanceof ItemCaptureException)) break;
            Throwable cause = current.getCause();
            if (cause == null) break;
            current = cause;
        }
        return new PreparationFailure(stage, kind, reason, exceptionTypes(failure));
    }

    private static List<String> exceptionTypes(Throwable failure) {
        var types = new ArrayList<String>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (@Nullable Throwable current = failure;
                current != null && visited.add(current);
                current = current.getCause()) types.add(current.getClass().getName());
        return List.copyOf(types);
    }

    String reasonTag() {
        return reason.name().toLowerCase(Locale.ROOT);
    }

    /** What the job row records as its error message. */
    String message() {
        return "The job failed before execution " + stage.phrase + ": " + kind.phrase + ".";
    }
}
