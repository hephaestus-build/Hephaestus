package de.tum.cit.aet.hephaestus.observability;

/**
 * The names of the log context (MDC) keys that a scope sets for every record inside it. A record writes them in its
 * {@code context} object; the operator contract that lists them is docs/admin/log-records.md § Fields. A key holds an
 * id or a closed code, never a login, an email address or text that a person wrote.
 *
 * <p>{@code core} cannot depend on this module, so the outbox writes the {@code action.*} keys as literals.
 */
public final class StructuredLogKeys {

    public static final String JOB_ID = "job.id";
    public static final String JOB_ATTEMPT = "job.attempt";
    /** Micrometer Tracing's name; a record writes it as ECS {@code trace.id}. */
    public static final String TRACE_ID = "traceId";

    public static final String TRACE_FLAGS = "traceFlags";
    /** Micrometer Tracing's name; a record writes it as ECS {@code span.id}. */
    public static final String SPAN_ID = "spanId";

    public static final String WORKSPACE_ID = "workspace.id";
    /** The GitHub App installation of a sync run. */
    public static final String INSTALLATION_ID = "installation.id";

    public static final String PROXY_PRINCIPAL = "proxy.principal";
    public static final String PROXY_API_PROTOCOL = "proxy.api_protocol";
    /** The agent run whose holder writes the record. */
    public static final String RUN_ID = "run.id";
    /** The lease epoch of that holder: two records of one run with different epochs come from different claims. */
    public static final String RUN_EPOCH = "run.epoch";

    /** The subject of the run, such as {@code review:<id>}. */
    public static final String SUBJECT_KEY = "subject.key";
    /** The agent sandbox that the record is about: its claim's name, which is also its Pod's. */
    public static final String SANDBOX_NAME = "sandbox.name";
    /** The review whose subject is running. */
    public static final String REVIEW_ID = "review.id";

    private StructuredLogKeys() {}
}
