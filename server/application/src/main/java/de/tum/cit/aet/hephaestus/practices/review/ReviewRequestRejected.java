package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** A request stopped before any review rows were written. */
public final class ReviewRequestRejected extends ErrorResponseException {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        REQUEST_CONFLICT,
        REF_UNSUPPORTED,
        VERSION_CHANGED,
        WORKSPACE_INACTIVE,
        SCAN_ACTIVE,
        BUDGET_UNAVAILABLE,
        ITEM_INELIGIBLE,
        MODEL_UNAVAILABLE,
        SOURCE_UNAVAILABLE;

        public boolean transientReason() {
            return this == BUDGET_UNAVAILABLE
                    || this == MODEL_UNAVAILABLE
                    || this == SOURCE_UNAVAILABLE
                    || this == SCAN_ACTIVE;
        }
    }

    private final Reason reason;
    private final @Nullable ReviewRequestReason eventReason;

    public ReviewRequestRejected(Reason reason, String message) {
        this(reason, message, null);
    }

    public ReviewRequestRejected(Reason reason, String message, @Nullable ReviewRequestReason eventReason) {
        this(reason, message, eventReason, null);
    }

    /** @param cause the failure this rejection settles; it stays out of the problem detail */
    public ReviewRequestRejected(
            Reason reason, String message, @Nullable ReviewRequestReason eventReason, @Nullable Throwable cause) {
        super(status(reason), ProblemDetail.forStatusAndDetail(status(reason), message), cause);
        this.eventReason = eventReason;
        this.reason = reason;
    }

    /**
     * A spent or unverifiable monthly AI budget. The reader cannot tell which cap stopped the review, or
     * whether its spend is unconfirmed, so the detail sends them to the page that says so and offers the fix.
     */
    public static ReviewRequestRejected budgetUnavailable() {
        return new ReviewRequestRejected(
                Reason.BUDGET_UNAVAILABLE,
                "This workspace’s monthly AI budget does not allow another review now. A workspace admin can see why,"
                        + " and how to resume, under AI usage.");
    }

    private static HttpStatus status(Reason reason) {
        return switch (reason) {
            case REQUEST_CONFLICT, VERSION_CHANGED, WORKSPACE_INACTIVE, SCAN_ACTIVE -> HttpStatus.CONFLICT;
            case BUDGET_UNAVAILABLE -> HttpStatus.TOO_MANY_REQUESTS;
            case ITEM_INELIGIBLE, REF_UNSUPPORTED -> HttpStatus.UNPROCESSABLE_CONTENT;
            case MODEL_UNAVAILABLE, SOURCE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    public @Nullable ReviewRequestReason eventReason() {
        return eventReason;
    }

    public Reason reason() {
        return reason;
    }
}
