package de.tum.cit.aet.hephaestus.observability;

public final class StructuredLogKeys {

    public static final String JOB_ID = "job.id";
    public static final String TRACE_ID = "traceId";
    public static final String TRACE_FLAGS = "traceFlags";
    public static final String SPAN_ID = "spanId";
    public static final String WORKSPACE_ID = "workspace.id";
    /** The agent run whose holder writes the record. */
    public static final String RUN_ID = "run.id";
    /** The lease epoch of that holder: two records of one run with different epochs come from different claims. */
    public static final String RUN_EPOCH = "run.epoch";
    /** The subject of the run, such as {@code review:<id>}. */
    public static final String SUBJECT_KEY = "subject.key";

    private StructuredLogKeys() {}
}
