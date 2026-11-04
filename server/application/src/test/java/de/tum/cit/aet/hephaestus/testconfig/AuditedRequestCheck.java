package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.audit.ConfigAuditEvent;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditAction;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionAudit;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Proves at test time what {@code AuditByDefaultArchTest} cannot: that an {@link Audited} handler which
 * answers 2xx wrote exactly one row of the ledger and type it declares. The architecture test reads
 * only the call graph, and only for the config audit ledger.
 *
 * <p>The window is the handler call on the server thread, so it closes before the response is written
 * and the client cannot see the response before a violation is recorded. A config audit update with
 * no changed key writes no row by design; such a request counts when the recorder was reached.
 *
 * <p>Test-only. The integration base classes import it, and fail the test that made a violating
 * request ({@link #assertNoViolations()}). Each JVM also writes the handlers it saw answer 2xx to
 * {@code build/audited-coverage/}, which tells which declared endpoints no integration test calls. That list is
 * advisory: nothing reads it, and files from earlier runs stay until {@code clean}.
 *
 * <p>It checks only handlers that carry {@link Audited} directly. A handler that carries it through a
 * meta-annotation, such as {@code @UserViewRead}, is not checked, because its step-up and view rows are
 * written outside the handler call.
 */
@Aspect
@TestComponent
public class AuditedRequestCheck {

    private static final Logger log = LoggerFactory.getLogger(AuditedRequestCheck.class);
    private static final ThreadLocal<@Nullable Window> WINDOW = new ThreadLocal<>();
    private static final Queue<Violation> VIOLATIONS = new ConcurrentLinkedQueue<>();
    private static final Set<String> COVERED = ConcurrentHashMap.newKeySet();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(AuditedRequestCheck::writeCoverage));
    }

    /** A 2xx request of {@code handler} that wrote {@code matching} rows of its type, of {@code written} audit rows. */
    private record Violation(String handler, long matching, int written, String message) {}

    /** One row a ledger wrote: its ledger and its type, or the empty type for an untyped ledger. */
    private record Row(AuditLedger ledger, String type) {}

    private static final class Window {
        private final List<Row> rows = new ArrayList<>();
        /**
         * By entity type, the config audit updates the recorder reached minus those it saved: the ones it
         * skipped because no key changed. The save is counted inside the record call, so each update nets
         * to 0 or 1.
         */
        private final Map<String, Integer> unchangedConfigUpdates = new HashMap<>();
    }

    @Around("@annotation(audited)")
    public @Nullable Object checkAuditedHandler(ProceedingJoinPoint call, Audited audited) throws Throwable {
        Window window = new Window();
        @Nullable Window outer = WINDOW.get();
        WINDOW.set(window);
        Object result;
        try {
            result = call.proceed();
        } finally {
            WINDOW.set(outer);
        }
        Method handler = ((MethodSignature) call.getSignature()).getMethod();
        if (isSuccess(handler, result)) {
            String name = handler.getDeclaringClass().getName() + "." + handler.getName();
            COVERED.add(name);
            violation(name, audited, window).ifPresent(VIOLATIONS::add);
        }
        return result;
    }

    @AfterReturning(
            "execution(void de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort.record(..)) && args(entry)")
    public void countConfigRecord(ConfigAuditEntry entry) {
        @Nullable Window window = WINDOW.get();
        if (window != null && entry.action() == ConfigAuditAction.UPDATED) {
            window.unchangedConfigUpdates.merge(entry.entityType().name(), 1, Integer::sum);
        }
    }

    @AfterReturning("execution(* org.springframework.data.repository.CrudRepository+.save*(..)) && args(saved)")
    public void countRow(Object saved) {
        @Nullable Window window = WINDOW.get();
        if (window == null) {
            return;
        }
        for (Object entity : saved instanceof Iterable<?> many ? many : List.of(saved)) {
            switch (entity) {
                case ConfigAuditEvent row -> {
                    window.rows.add(new Row(
                            AuditLedger.CONFIG_AUDIT, row.getEntityType().name()));
                    if (row.getAction() == ConfigAuditAction.UPDATED) {
                        window.unchangedConfigUpdates.merge(row.getEntityType().name(), -1, Integer::sum);
                    }
                }
                case AuthEvent row ->
                    window.rows.add(
                            new Row(AuditLedger.AUTH_EVENT, row.getEventType().name()));
                case ConnectionAudit ignored -> window.rows.add(new Row(AuditLedger.CONNECTION_AUDIT, ""));
                default -> {
                    // Not an audit row.
                }
            }
        }
    }

    private static Optional<Violation> violation(String name, Audited audited, Window window) {
        long matching = window.rows.stream()
                .filter(row -> row.ledger() == audited.ledger())
                .filter(row -> audited.type().isEmpty() || row.type().equals(audited.type()))
                .count();
        if (matching == 1) {
            return Optional.empty();
        }
        if (matching == 0
                && audited.ledger() == AuditLedger.CONFIG_AUDIT
                && window.unchangedConfigUpdates.getOrDefault(audited.type(), 0) > 0) {
            return Optional.empty();
        }
        return Optional.of(new Violation(
                name,
                matching,
                window.rows.size(),
                "%s answered 2xx and wrote %d %s rows of type '%s' (expected exactly one); rows written: %s"
                        .formatted(name, matching, audited.ledger(), audited.type(), window.rows)));
    }

    private static boolean isSuccess(Method handler, @Nullable Object result) {
        if (result instanceof ResponseEntity<?> entity) {
            return entity.getStatusCode().is2xxSuccessful();
        }
        ResponseStatus status = handler.getAnnotation(ResponseStatus.class);
        return status == null || status.code().is2xxSuccessful();
    }

    /**
     * Accepts the requests of one handler since the last check that wrote no row at all. The check cannot
     * tell such a request from one that failed to write its row, so the calling test vouches that the
     * request changed nothing, such as an unchanged selection. A request that wrote a wrong or a second row
     * still fails.
     */
    public static void acceptUnchanged(Class<?> controller, String method) {
        String handler = controller.getName() + "." + method;
        VIOLATIONS.removeIf(violation -> violation.handler().equals(handler) && violation.written() == 0);
    }

    /** Forgets violations that a request outside any test, such as in {@code @BeforeAll}, left behind. */
    public static void reset() {
        VIOLATIONS.clear();
    }

    /** Fails with every violation since the last call, and forgets them. */
    public static void assertNoViolations() {
        List<String> found = new ArrayList<>();
        for (Violation violation = VIOLATIONS.poll(); violation != null; violation = VIOLATIONS.poll()) {
            found.add(violation.message());
        }
        if (!found.isEmpty()) {
            throw new AssertionError(
                    "An @Audited endpoint answered 2xx without its audit row:\n  " + String.join("\n  ", found));
        }
    }

    private static void writeCoverage() {
        if (COVERED.isEmpty()) {
            return;
        }
        try {
            Path directory = Files.createDirectories(Path.of("build", "audited-coverage"));
            Files.write(
                    directory.resolve(UUID.randomUUID() + ".txt"),
                    COVERED.stream().sorted().toList());
        } catch (IOException e) {
            // A shutdown hook cannot fail the run; the coverage report is advisory.
            log.warn("audited-coverage: could not write the report", e);
        }
    }
}
