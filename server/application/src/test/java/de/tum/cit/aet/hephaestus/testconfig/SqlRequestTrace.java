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
    private final Map<String, CompletableFuture<List<String>>> requests = new ConcurrentHashMap<>();

    SqlRequestTrace(SqlStatementCounter statements) {
        this.statements = statements;
    }

    /** Runs one exchange through a client that tags its request, then returns the SQL that the request prepared. */
    public <T> SqlStatementCounter.Measurement<T> measure(WebTestClient http, Function<WebTestClient, T> exchange) {
        String id = UUID.randomUUID().toString();
        try {
            T value = exchange.apply(http.mutate().defaultHeader(HEADER, id).build());
            // The client can read a complete body before the server thread leaves this filter.
            List<String> sql = request(id).get(30, TimeUnit.SECONDS);
            return new SqlStatementCounter.Measurement<>(value, sql);
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
        if (id == null) {
            chain.doFilter(request, response);
            return;
        }
        try {
            var measured = statements.measure(() -> {
                try {
                    chain.doFilter(request, response);
                } catch (IOException | ServletException failure) {
                    throw new FailedRequest(failure);
                }
                return id;
            });
            request(id).complete(measured.sql());
        } catch (FailedRequest failure) {
            Throwable cause = Objects.requireNonNull(failure.getCause());
            request(id).completeExceptionally(cause);
            if (cause instanceof IOException io) throw io;
            throw (ServletException) cause;
        } catch (RuntimeException | Error failure) {
            request(id).completeExceptionally(failure);
            throw failure;
        }
    }

    private CompletableFuture<List<String>> request(String id) {
        return requests.computeIfAbsent(id, key -> new CompletableFuture<>());
    }

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
