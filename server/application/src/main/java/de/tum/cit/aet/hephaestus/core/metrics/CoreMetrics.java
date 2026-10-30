package de.tum.cit.aet.hephaestus.core.metrics;

public final class CoreMetrics {

    public static final String AUTH_AUDIT_WRITE_FAILED = "auth.audit.write_failed";
    public static final String AUTH_ISSUED_JWT_PRUNED = "auth.issued_jwt.pruned";
    public static final String AUTH_LOGIN = "auth.login";
    public static final String AUTH_RATELIMIT_BACKEND_ERROR = "auth.ratelimit.backend_error";
    public static final String AUTH_RATELIMIT_BLOCKED = "auth.ratelimit.blocked";
    public static final String AUTH_REVOCATION_CHECK_FAILED = "auth.revocation.check_failed";
    public static final String AUTH_STEP_UP_DENIED = "auth.step_up.denied";
    public static final String AUTH_TOKEN_REFRESH = "auth.token.refresh";
    public static final String AUTH_TOKEN_REFRESH_RESULT = "auth.token.refresh.result";
    public static final String DATABASE_WAL_ARCHIVE_FAILING = "database.wal.archive.failing";
    public static final String DATABASE_WAL_ARCHIVE_SAMPLE_FAILURES = "database.wal.archive.sample.failures";
    public static final String PRIVACY_JOB_AFFECTED = "privacy.job.affected";
    public static final String PRIVACY_JOB_COMPLETED = "privacy.job.completed";
    public static final String TENANCY_PARSE_FAILURE_TOTAL = "tenancy.parse_failure.total";
    public static final String TENANCY_VIOLATION_TOTAL = "tenancy.violation.total";

    private CoreMetrics() {}
}
