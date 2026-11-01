package de.tum.cit.aet.hephaestus.core.exception;

import java.net.URI;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Every RFC 9457 problem {@code type} that a client can branch on. The slug is the machine-readable
 * code. Other problem members carry named data, never a second code. A problem without a distinct kind
 * keeps {@code about:blank}, so its status is its kind. Renaming a slug breaks clients.
 */
public enum ProblemType {
    AGENT_BINDING_SLOT_MISMATCH,
    AGENT_JOB_STATE_CONFLICT,
    CONCURRENT_MODIFICATION,
    CSRF_TOKEN_REJECTED,
    FEEDBACK_WITHHELD,
    LLM_CALL_POOL_IN_USE,
    LLM_CALL_POOL_SLUG_CONFLICT,
    LLM_CONNECTION_IN_USE,
    LLM_CONNECTION_SLUG_CONFLICT,
    LLM_MODEL_IN_USE,
    LLM_MODEL_SLUG_CONFLICT,
    LLM_MODEL_UPSTREAM_ID_CONFLICT,
    MENTOR_LEASE_LOST,
    MENTOR_REFUSED,
    MENTOR_REJECTED,
    OAUTH_CALLBACK_FAILED,
    OUTLINE_RATE_LIMITED,
    OUTLINE_UNREACHABLE,
    PASSKEY_REQUIRED,
    REVIEW_BATCH_CONFLICT,
    REVIEW_SCHEDULE_CONFLICT,
    STEP_UP_REQUIRED,
    SYNC_NOT_SUPPORTED,
    UNKNOWN_OUTLINE_COLLECTION,
    WORKSPACE_BUDGET_EXCEEDED;

    /** The contributor guide lists each slug under this address. */
    private static final String BASE = "https://docs.hephaestus.build/contributor/api-error-handling#";

    private final URI uri = URI.create(BASE + slug());

    /** The lowercase, hyphenated code that ends the type URI. */
    public String slug() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public URI uri() {
        return uri;
    }

    /** A problem of this type with the given status, title and detail. */
    public ProblemDetail problem(HttpStatusCode status, String title, @Nullable String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(uri);
        problem.setTitle(title);
        return problem;
    }
}
