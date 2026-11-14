package de.tum.cit.aet.hephaestus.testconfig;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.transport.RequestReplySenderContext;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Fails the integration test during which an outbound HTTP request started while a database transaction was open.
 * Such a request holds a pooled connection and the transaction's locks for as long as the other service takes to
 * answer ({@code docs/contributor/transactions.md}). Production counts the same requests in
 * {@code HttpRequestsInTransactions}; this check turns each one into a failure that names its caller.
 *
 * <p>It sees every client that Spring's injected builders build ({@code HttpClientConstructionArchTest}), on the thread
 * that starts the request. A transaction that the test itself opens with {@code @Transactional} is not the application's,
 * so a request inside it is not counted. The code it calls may still do the same in production; other tests cover it.
 * In tests, an {@code @Async} body runs on the caller's thread, inside the caller's transaction, where production runs it
 * on an executor thread without one: such a failure names a test artifact, and the caller decides.
 */
@TestComponent
public class HttpInTransactionCheck implements ObservationHandler<RequestReplySenderContext<?, ?>> {

    private static final Queue<String> VIOLATIONS = new ConcurrentLinkedQueue<>();

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof RequestReplySenderContext<?, ?>;
    }

    @Override
    public void onStart(RequestReplySenderContext<?, ?> context) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || TestTransaction.isActive()) return;
        VIOLATIONS.add("%s in transaction %s, called from:%n%s"
                .formatted(
                        context.getName(),
                        TransactionSynchronizationManager.getCurrentTransactionName(),
                        applicationFrames()));
    }

    /** The application's own frames of the current stack, the place to fix. */
    private static String applicationFrames() {
        return Arrays.stream(Thread.currentThread().getStackTrace())
                .filter(frame -> frame.getClassName().startsWith("de.tum.cit.aet.hephaestus.")
                        && !frame.getClassName().startsWith(HttpInTransactionCheck.class.getName()))
                .limit(12)
                .map(frame -> "    at " + frame)
                .collect(Collectors.joining(System.lineSeparator()));
    }

    public static void reset() {
        VIOLATIONS.clear();
    }

    public static void assertNoViolations() {
        List<String> found = List.copyOf(VIOLATIONS);
        VIOLATIONS.clear();
        assertThat(found)
                .as("outbound HTTP requests that started inside a database transaction")
                .isEmpty();
    }
}
