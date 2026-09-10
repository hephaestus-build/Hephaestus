package de.tum.cit.aet.hephaestus.integration.scm.github.common;

import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.JITTER_FACTOR;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.MAX_PAGINATION_PAGES;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_INITIAL_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_RETRIES;

import de.tum.cit.aet.hephaestus.integration.scm.common.ScmTransportErrors;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPageInfo;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Reusable pagination helper for GitHub GraphQL API queries.
 * <p>
 * This helper encapsulates the common pagination loop pattern used across
 * multiple sync services, reducing code duplication and ensuring consistent
 * handling of:
 * <ul>
 *   <li>Page counting with configurable maximum</li>
 *   <li>GraphQL request execution with timeout</li>
 *   <li>Response validation</li>
 *   <li>Rate limit tracking and critical threshold detection</li>
 *   <li>Page info extraction and cursor management</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * PaginationResult result = paginationHelper.paginate(
 *     PaginationRequest.<GHLabelConnection>builder()
 *         .client(client)
 *         .scopeId(scopeId)
 *         .documentName("GetRepositoryLabels")
 *         .variables(Map.of("owner", owner, "name", name, "first", PAGE_SIZE))
 *         .timeout(timeout)
 *         .connectionFieldPath("repository.labels")
 *         .connectionType(GHLabelConnection.class)
 *         .pageInfoExtractor(GHLabelConnection::getPageInfo)
 *         .pageProcessor(connection -> {
 *             for (var label : connection.getNodes()) {
 *                 processLabel(label);
 *             }
 *         })
 *         .contextDescription("labels for " + repoName)
 *         .build()
 * );
 * }</pre>
 *
 * <h2>When to Use This Helper</h2>
 * <p>
 * Use this helper for simple pagination loops where:
 * <ul>
 *   <li>Each page can be processed independently</li>
 *   <li>No transaction boundaries need to be managed per-page</li>
 *   <li>No retry logic or checkpoint persistence is needed</li>
 * </ul>
 * <p>
 * For more complex scenarios (like GitHubIssueSyncService with retries,
 * per-page transactions, and cursor checkpointing), consider using this
 * helper as a building block or implementing pagination manually.
 *
 * @see PaginationRequest for configuration options
 * @see PaginationResult for result details
 */
@Component
@Slf4j
public final class GraphQlPaginationHelper {

    private static final int MAX_RETRY_ATTEMPTS = 3;

    private final GitHubGraphQlClientProvider graphQlClientProvider;
    private final GitHubGraphQlSyncCoordinator graphQlSyncHelper;

    public GraphQlPaginationHelper(
            GitHubGraphQlClientProvider graphQlClientProvider, GitHubGraphQlSyncCoordinator graphQlSyncHelper) {
        this.graphQlClientProvider = graphQlClientProvider;
        this.graphQlSyncHelper = graphQlSyncHelper;
    }

    /**
     * Executes a paginated GraphQL query, processing each page with the provided processor.
     *
     * @param <T>     the connection type (e.g., GHLabelConnection)
     * @param request the pagination request configuration
     * @return result containing page count and termination reason
     */
    public <T> PaginationResult paginate(PaginationRequest<T> request) {
        String cursor = request.initialCursor();
        boolean hasNextPage = true;
        int pageCount = 0;
        int retryAttempt = 0;

        while (hasNextPage) {
            // Check for thread interruption (e.g., during application shutdown)
            if (Thread.interrupted()) {
                log.info(
                        "Pagination interrupted (shutdown requested): context={}, pagesProcessed={}",
                        request.contextDescription(),
                        pageCount);
                // Preserve interrupt status for caller
                Thread.currentThread().interrupt();
                return new PaginationResult(pageCount, TerminationReason.INTERRUPTED);
            }

            pageCount++;

            if (pageCount > request.maxPages()) {
                log.warn(
                        "Reached maximum pagination limit: context={}, limit={}",
                        request.contextDescription(),
                        request.maxPages());
                return new PaginationResult(pageCount - 1, TerminationReason.MAX_PAGES_REACHED);
            }

            // Build and execute the GraphQL request
            var requestBuilder = request.client().documentName(request.documentName());

            // Add all variables
            for (Map.Entry<String, Object> entry : request.variables().entrySet()) {
                requestBuilder = requestBuilder.variable(entry.getKey(), entry.getValue());
            }

            // Add cursor for pagination (null on first page)
            var finalRequestBuilder = requestBuilder.variable("after", cursor);
            final int currentPage = pageCount; // Make final copy for lambda

            // Use Mono.defer() to wrap the entire execute() call so retries cover body streaming.
            // This is CRITICAL: WebClient ExchangeFilterFunction retries only cover the HTTP exchange,
            // not body consumption. PrematureCloseException occurs DURING body streaming.
            ClientGraphQlResponse response = Mono.defer(() -> finalRequestBuilder.execute())
                    .retryWhen(Retry.backoff(TRANSPORT_MAX_RETRIES, TRANSPORT_INITIAL_BACKOFF)
                            .maxBackoff(TRANSPORT_MAX_BACKOFF)
                            .jitter(JITTER_FACTOR)
                            .filter(ScmTransportErrors::isTransportError)
                            .doBeforeRetry(signal -> log.warn(
                                    "Retrying GraphQL request after transport error: context={}, page={}, attempt={}, error={}",
                                    request.contextDescription(),
                                    currentPage,
                                    signal.totalRetries() + 1,
                                    signal.failure().getMessage())))
                    .block(request.timeout());

            if (response == null) {
                log.warn("Received null GraphQL response: context={}", request.contextDescription());
                return new PaginationResult(pageCount, TerminationReason.INVALID_RESPONSE);
            }

            if (!response.isValid()) {
                var classification = graphQlSyncHelper.classifyGraphQlErrors(response);
                if (classification != null) {
                    boolean shouldRetry = graphQlSyncHelper.handleGraphQlClassification(
                            new GitHubGraphQlSyncCoordinator.GraphQlClassificationContext(
                                    classification,
                                    retryAttempt,
                                    MAX_RETRY_ATTEMPTS,
                                    request.contextDescription(),
                                    "context",
                                    request.contextDescription(),
                                    log));
                    if (shouldRetry) {
                        retryAttempt++;
                        continue;
                    }
                    return new PaginationResult(
                            pageCount,
                            classification.category() == GitHubExceptionClassifier.Category.RATE_LIMITED
                                    ? TerminationReason.RATE_LIMIT_CRITICAL
                                    : TerminationReason.INVALID_RESPONSE);
                }
                log.warn(
                        "Received invalid GraphQL response: context={}, errors={}",
                        request.contextDescription(),
                        response.getErrors());
                return new PaginationResult(pageCount, TerminationReason.INVALID_RESPONSE);
            }

            // Track rate limit
            graphQlClientProvider.trackRateLimit(request.scopeId(), response);

            // Check rate limit threshold
            if (graphQlClientProvider.isRateLimitCritical(request.scopeId())) {
                if (!graphQlSyncHelper.waitForRateLimitIfNeeded(
                        request.scopeId(),
                        request.contextDescription(),
                        "context",
                        request.contextDescription(),
                        log)) {
                    return new PaginationResult(pageCount, TerminationReason.RATE_LIMIT_CRITICAL);
                }
            }

            // Extract the connection from the response
            T connection = response.field(request.connectionFieldPath()).toEntity(request.connectionType());

            if (connection == null) {
                log.debug(
                        "Connection is null, ending pagination: context={}, pageCount={}",
                        request.contextDescription(),
                        pageCount);
                return new PaginationResult(pageCount, TerminationReason.NULL_CONNECTION);
            }

            // Let the processor handle the page (it may choose to break early by returning false)
            boolean shouldContinue = request.pageProcessor().processPage(connection);
            if (!shouldContinue) {
                log.debug(
                        "Processor requested early termination: context={}, pageCount={}",
                        request.contextDescription(),
                        pageCount);
                return new PaginationResult(pageCount, TerminationReason.PROCESSOR_STOP);
            }

            // Extract page info for next iteration
            GHPageInfo pageInfo = request.pageInfoExtractor().apply(connection);
            hasNextPage = pageInfo != null && Boolean.TRUE.equals(pageInfo.getHasNextPage());
            cursor = pageInfo != null ? pageInfo.getEndCursor() : null;
            retryAttempt = 0;
        }

        return new PaginationResult(pageCount, TerminationReason.COMPLETED);
    }

    /**
     * Configuration for a pagination request.
     *
     * @param <T> the connection type
     */
    public record PaginationRequest<T>(
            HttpGraphQlClient client,
            Long scopeId,
            String documentName,
            Map<String, Object> variables,
            Duration timeout,
            String connectionFieldPath,
            Class<T> connectionType,
            Function<T, GHPageInfo> pageInfoExtractor,
            PageProcessor<T> pageProcessor,
            String contextDescription,
            @Nullable String initialCursor,
            int maxPages) {
        /**
         * Creates a builder for PaginationRequest.
         *
         * @param <T> the connection type
         * @return a new builder
         */
        public static <T> Builder<T> builder() {
            return new Builder<>();
        }

        /**
         * Builder for PaginationRequest.
         *
         * @param <T> the connection type
         */
        public static class Builder<T> {

            private @Nullable HttpGraphQlClient client;
            private @Nullable Long scopeId;
            private @Nullable String documentName;
            private @Nullable Map<String, Object> variables;
            private @Nullable Duration timeout;
            private @Nullable String connectionFieldPath;
            private @Nullable Class<T> connectionType;
            private @Nullable Function<T, GHPageInfo> pageInfoExtractor;
            private @Nullable PageProcessor<T> pageProcessor;
            private String contextDescription = "pagination";
            private @Nullable String initialCursor;
            private int maxPages = MAX_PAGINATION_PAGES;

            public Builder<T> client(HttpGraphQlClient client) {
                this.client = client;
                return this;
            }

            public Builder<T> scopeId(Long scopeId) {
                this.scopeId = scopeId;
                return this;
            }

            public Builder<T> documentName(String documentName) {
                this.documentName = documentName;
                return this;
            }

            public Builder<T> variables(Map<String, Object> variables) {
                this.variables = variables;
                return this;
            }

            public Builder<T> timeout(Duration timeout) {
                this.timeout = timeout;
                return this;
            }

            public Builder<T> connectionFieldPath(String connectionFieldPath) {
                this.connectionFieldPath = connectionFieldPath;
                return this;
            }

            public Builder<T> connectionType(Class<T> connectionType) {
                this.connectionType = connectionType;
                return this;
            }

            public Builder<T> pageInfoExtractor(Function<T, GHPageInfo> pageInfoExtractor) {
                this.pageInfoExtractor = pageInfoExtractor;
                return this;
            }

            /**
             * Sets a simple page processor that always continues to the next page.
             * Use this when you just need to process each page without early termination.
             *
             * @param processor consumer that processes each page
             * @return this builder
             */
            public Builder<T> pageProcessor(Consumer<T> processor) {
                this.pageProcessor = connection -> {
                    processor.accept(connection);
                    return true;
                };
                return this;
            }

            /**
             * Sets a page processor that can control pagination flow.
             * Return false from processPage to stop pagination early.
             *
             * @param processor the page processor
             * @return this builder
             */
            public Builder<T> pageProcessorWhile(PageProcessor<T> processor) {
                this.pageProcessor = processor;
                return this;
            }

            public Builder<T> contextDescription(String contextDescription) {
                this.contextDescription = contextDescription;
                return this;
            }

            public Builder<T> initialCursor(@Nullable String initialCursor) {
                this.initialCursor = initialCursor;
                return this;
            }

            public Builder<T> maxPages(int maxPages) {
                this.maxPages = maxPages;
                return this;
            }

            public PaginationRequest<T> build() {
                if (client == null) {
                    throw new IllegalStateException("client is required");
                }
                if (scopeId == null) {
                    throw new IllegalStateException("scopeId is required");
                }
                if (documentName == null) {
                    throw new IllegalStateException("documentName is required");
                }
                if (variables == null) {
                    throw new IllegalStateException("variables is required");
                }
                if (timeout == null) {
                    throw new IllegalStateException("timeout is required");
                }
                if (connectionFieldPath == null) {
                    throw new IllegalStateException("connectionFieldPath is required");
                }
                if (connectionType == null) {
                    throw new IllegalStateException("connectionType is required");
                }
                if (pageInfoExtractor == null) {
                    throw new IllegalStateException("pageInfoExtractor is required");
                }
                if (pageProcessor == null) {
                    throw new IllegalStateException("pageProcessor is required");
                }

                return new PaginationRequest<>(
                        client,
                        scopeId,
                        documentName,
                        variables,
                        timeout,
                        connectionFieldPath,
                        connectionType,
                        pageInfoExtractor,
                        pageProcessor,
                        contextDescription,
                        initialCursor,
                        maxPages);
            }
        }
    }

    /**
     * Functional interface for processing a page of results.
     * <p>
     * Return true to continue to the next page, or false to stop pagination early.
     *
     * @param <T> the connection type
     */
    @FunctionalInterface
    public interface PageProcessor<T> {
        /**
         * Processes a page of results.
         *
         * @param connection the connection containing the page data
         * @return true to continue pagination, false to stop
         */
        boolean processPage(T connection);
    }

    /**
     * Result of a pagination operation.
     *
     * @param pagesProcessed the number of pages successfully processed
     * @param terminationReason why pagination stopped
     */
    public record PaginationResult(int pagesProcessed, TerminationReason terminationReason) {
        /**
         * Returns true if pagination completed normally (reached end of data).
         */
        public boolean isComplete() {
            return terminationReason == TerminationReason.COMPLETED;
        }

        /**
         * Returns true if pagination was aborted due to an error or limit.
         */
        public boolean isAborted() {
            return (terminationReason != TerminationReason.COMPLETED
                    && terminationReason != TerminationReason.PROCESSOR_STOP);
        }

        /**
         * Returns true if pagination stopped due to a transient error that may be retried.
         */
        public boolean isTransientError() {
            return terminationReason == TerminationReason.TRANSIENT_ERROR;
        }
    }

    /**
     * Reasons why pagination terminated.
     */
    public enum TerminationReason {
        /** Reached the end of paginated data normally. */
        COMPLETED,
        /** Reached the maximum page limit. */
        MAX_PAGES_REACHED,
        /** Received an invalid or null GraphQL response. */
        INVALID_RESPONSE,
        /** Rate limit reached critical threshold. */
        RATE_LIMIT_CRITICAL,
        /** Connection field was null in the response. */
        NULL_CONNECTION,
        /** Page processor requested early termination. */
        PROCESSOR_STOP,
        /** Transient error detected (timeout, rate limit, server error). */
        TRANSIENT_ERROR,
        /** Thread was interrupted (e.g., during application shutdown). */
        INTERRUPTED,
    }
}
