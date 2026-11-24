package de.tum.cit.aet.hephaestus.testconfig;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.core.Ordered;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Measures the SQL that one HTTP request prepares on its server thread, including the security and workspace filters.
 * Only requests that carry {@link #HEADER} are measured. An async dispatch, such as a stream, is not included.
 */
public class SqlRequestTrace extends OncePerRequestFilter implements Ordered {
    static final String HEADER = "X-Test-Sql-Trace";

    private final SqlStatementCounter statements;
    private final Map<String, Request> requests = new ConcurrentHashMap<>();

    SqlRequestTrace(SqlStatementCounter statements) {
        this.statements = statements;
    }

    /** Runs one exchange through a client that tags its request, then returns the SQL that the request prepared. */
    public <T> SqlStatementCounter.Measurement<T> measure(WebTestClient http, Function<WebTestClient, T> exchange) {
        return measure(http, exchange, false);
    }

    /** Like {@link #measure}, with the owner and read-only state of each statement's transaction. */
    public <T> SqlStatementCounter.Measurement<T> measureOwners(
            WebTestClient http, Function<WebTestClient, T> exchange) {
        return measure(http, exchange, true);
    }

    private <T> SqlStatementCounter.Measurement<T> measure(
            WebTestClient http, Function<WebTestClient, T> exchange, boolean owners) {
        String id = UUID.randomUUID().toString();
        Request request = new Request(owners, new CompletableFuture<>());
        requests.put(id, request);
        try {
            T value = exchange.apply(http.mutate().defaultHeader(HEADER, id).build());
            // The client can read a complete body before the server thread leaves this filter.
            List<SqlStatementCounter.Prepared> prepared = request.prepared().get(30, TimeUnit.SECONDS);
            return new SqlStatementCounter.Measurement<>(value, prepared);
        } catch (ExecutionException failure) {
            throw new IllegalStateException("The measured request failed", failure.getCause());
        } catch (TimeoutException failure) {
            throw new IllegalStateException("The measured request did not leave the server filter", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while the measured request ran", failure);
        } finally {
            requests.remove(id);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = request.getHeader(HEADER);
        Request measured = id == null ? null : requests.get(id);
        if (measured == null) {
            chain.doFilter(request, response);
            return;
        }
        Supplier<String> exchange = () -> {
            try {
                chain.doFilter(request, response);
            } catch (IOException | ServletException failure) {
                throw new FailedRequest(failure);
            }
            return "measured";
        };
        try {
            measured.prepared()
                    .complete((measured.owners() ? statements.measureOwners(exchange) : statements.measure(exchange))
                            .prepared());
        } catch (FailedRequest failure) {
            Throwable cause = Objects.requireNonNull(failure.getCause());
            measured.prepared().completeExceptionally(cause);
            if (cause instanceof IOException io) throw io;
            throw (ServletException) cause;
        } catch (RuntimeException | Error failure) {
            measured.prepared().completeExceptionally(failure);
            throw failure;
        }
    }

    /** A tagged request: whether it reads owners, and the statements it prepared once it leaves this filter. */
    private record Request(boolean owners, CompletableFuture<List<SqlStatementCounter.Prepared>> prepared) {}

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /** Carries a checked failure of the filter chain out of the measured block. */
    private static final class FailedRequest extends RuntimeException {
        private static final long serialVersionUID = 1L;

        FailedRequest(Exception cause) {
            super(cause);
        }
    }
}
