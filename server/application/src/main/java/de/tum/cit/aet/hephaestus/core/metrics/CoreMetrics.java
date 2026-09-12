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
    public static final String PRIVACY_JOB_AFFECTED = "privacy.job.affected";
    public static final String PRIVACY_JOB_COMPLETED = "privacy.job.completed";
    public static final String TENANCY_PARSE_FAILURE_TOTAL = "tenancy.parse_failure.total";
    public static final String TENANCY_VIOLATION_TOTAL = "tenancy.violation.total";
    public static final String WORKER_HUB_BINARY_REFUSED = "worker.hub.binary.refused";
    public static final String WORKER_HUB_DRAINING_SIGNALLED = "worker.hub.draining.signalled";
    public static final String WORKER_HUB_FRAME_DECODE_FAILED = "worker.hub.frame.decode.failed";
    public static final String WORKER_HUB_FRAME_DISPATCH_FAILED = "worker.hub.frame.dispatch.failed";
    public static final String WORKER_HUB_HANDSHAKE_COMPLETED = "worker.hub.handshake.completed";
    public static final String WORKER_HUB_HELLO_TIMEOUT = "worker.hub.hello.timeout";
    public static final String WORKER_HUB_SESSIONS_ACTIVE = "worker.hub.sessions.active";
    public static final String WORKER_HUB_TRANSPORT_ERRORS = "worker.hub.transport.errors";
    public static final String WORKER_JWT_VERIFY = "worker.jwt.verify";
    public static final String WORKER_TOKEN_EXCHANGE = "worker.token.exchange";

    private CoreMetrics() {}
}
