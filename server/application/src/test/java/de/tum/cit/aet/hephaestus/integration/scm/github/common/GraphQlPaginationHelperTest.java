package de.tum.cit.aet.hephaestus.integration.scm.github.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.github.common.GraphQlPaginationHelper.PaginationRequest;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GraphQlPaginationHelper.PaginationResult;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GraphQlPaginationHelper.TerminationReason;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPageInfo;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.HttpGraphQlClient;
import reactor.core.publisher.Mono;

/**
 * Unit tests for {@link GraphQlPaginationHelper}.
 */
@ExtendWith(MockitoExtension.class)
@Tag("unit")
class GraphQlPaginationHelperTest {

    @Mock
    private GitHubGraphQlClientProvider graphQlClientProvider;

    @Mock
    private GitHubGraphQlSyncCoordinator graphQlSyncHelper;

    @Mock
    private HttpGraphQlClient client;

    @Mock
    private HttpGraphQlClient.RequestSpec requestSpec;

    private GraphQlPaginationHelper helper;

    private static final Long SCOPE_ID = 123L;
    private static final String DOCUMENT_NAME = "TestQuery";
    private static final String FIELD_PATH = "repository.items";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @BeforeEach
    void setUp() {
        helper = new GraphQlPaginationHelper(graphQlClientProvider, graphQlSyncHelper);
    }

    /**
     * Simple test connection class that mimics GraphQL connection types.
     */
    static class TestConnection {

        private final List<String> nodes;
        private final GHPageInfo pageInfo;

        TestConnection(List<String> nodes, GHPageInfo pageInfo) {
            this.nodes = nodes;
            this.pageInfo = pageInfo;
        }

        public List<String> getNodes() {
            return nodes;
        }

        public GHPageInfo getPageInfo() {
            return pageInfo;
        }
    }

    @Nested
    class PaginateTests {

        @Test
        void shouldProcessSinglePageAndComplete() {
            GHPageInfo pageInfo = pageInfo("cursor1", false);
            TestConnection connection = new TestConnection(List.of("item1", "item2"), pageInfo);

            ClientGraphQlResponse response = mockValidResponse(connection);
            mockClientExecution(response);

            AtomicInteger processedCount = new AtomicInteger(0);

            PaginationResult result = helper.paginate(createRequest(conn -> {
                processedCount.addAndGet(conn.getNodes().size());
                return true;
            }));

            assertThat(result.pagesProcessed()).isEqualTo(1);
            assertThat(result.terminationReason()).isEqualTo(TerminationReason.COMPLETED);
            assertThat(result.isComplete()).isTrue();
            assertThat(result.isAborted()).isFalse();
            assertThat(processedCount.get()).isEqualTo(2);
        }

        @Test
        void shouldPaginateThroughMultiplePages() {
            GHPageInfo pageInfo1 = pageInfo("cursor1", true);
            GHPageInfo pageInfo2 = pageInfo("cursor2", true);
            GHPageInfo pageInfo3 = pageInfo("cursor3", false);

            TestConnection connection1 = new TestConnection(List.of("item1"), pageInfo1);
            TestConnection connection2 = new TestConnection(List.of("item2"), pageInfo2);
            TestConnection connection3 = new TestConnection(List.of("item3"), pageInfo3);

            ClientGraphQlResponse response1 = mockValidResponse(connection1);
            ClientGraphQlResponse response2 = mockValidResponse(connection2);
            ClientGraphQlResponse response3 = mockValidResponse(connection3);

            mockClientExecutionSequence(response1, response2, response3);

            List<String> allItems = new ArrayList<>();

            PaginationResult result = helper.paginate(createRequest(conn -> {
                allItems.addAll(conn.getNodes());
                return true;
            }));

            assertThat(result.pagesProcessed()).isEqualTo(3);
            assertThat(result.terminationReason()).isEqualTo(TerminationReason.COMPLETED);
            assertThat(allItems).containsExactly("item1", "item2", "item3");
        }

        @Test
        void shouldStopWhenRateLimitIsCritical() {
            GHPageInfo pageInfo = pageInfo("cursor1", true);
            TestConnection connection = new TestConnection(List.of("item1"), pageInfo);
            ClientGraphQlResponse response = mockValidResponse(connection);
            mockClientExecution(response);

            when(graphQlClientProvider.isRateLimitCritical(SCOPE_ID)).thenReturn(true);

            PaginationResult result = helper.paginate(createRequest(conn -> true));

            assertThat(result.pagesProcessed()).isEqualTo(1);
            assertThat(result.terminationReason()).isEqualTo(TerminationReason.RATE_LIMIT_CRITICAL);
            assertThat(result.isAborted()).isTrue();
        }

        @Test
        void shouldStopWhenResponseIsInvalid() {
            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.isValid()).thenReturn(false);
            when(response.getErrors()).thenReturn(List.of());
            mockClientExecution(response);

            PaginationResult result = helper.paginate(createRequest(conn -> true));

            assertThat(result.pagesProcessed()).isEqualTo(1);
            assertThat(result.terminationReason()).isEqualTo(TerminationReason.INVALID_RESPONSE);
            assertThat(result.isAborted()).isTrue();
        }

        @Test
        void shouldStopWhenConnectionIsNull() {
            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            ClientResponseField field = mock(ClientResponseField.class);
            when(response.isValid()).thenReturn(true);
            when(response.field(FIELD_PATH)).thenReturn(field);
            when(field.toEntity(TestConnection.class)).thenReturn(null);
            mockClientExecution(response);

            PaginationResult result = helper.paginate(createRequest(conn -> true));

            assertThat(result.pagesProcessed()).isEqualTo(1);
            assertThat(result.terminationReason()).isEqualTo(TerminationReason.NULL_CONNECTION);
        }

        @Test
        void shouldStopWhenProcessorReturnsFalse() {
            GHPageInfo pageInfo = pageInfo("cursor1", true);
            TestConnection connection = new TestConnection(List.of("item1"), pageInfo);
            ClientGraphQlResponse response = mockValidResponse(connection);
            mockClientExecution(response);

            PaginationResult result = helper.paginate(
                    createRequest(conn -> false) // Processor requests stop
                    );

            assertThat(result.pagesProcessed()).isEqualTo(1);
            assertThat(result.terminationReason()).isEqualTo(TerminationReason.PROCESSOR_STOP);
            assertThat(result.isAborted()).isFalse(); // PROCESSOR_STOP is not considered aborted
        }

        @Test
        void shouldRespectMaxPagesLimit() {
            // Given - always return hasNextPage=true
            GHPageInfo pageInfo = pageInfo("cursor", true);
            TestConnection connection = new TestConnection(List.of("item"), pageInfo);
            ClientGraphQlResponse response = mockValidResponse(connection);

            // Mock to always return the same response (infinite pagination scenario)
            when(client.documentName(DOCUMENT_NAME)).thenReturn(requestSpec);
            when(requestSpec.variable(any(), any())).thenReturn(requestSpec);
            when(requestSpec.execute()).thenReturn(Mono.just(response));

            AtomicInteger pageCount = new AtomicInteger(0);

            PaginationResult result = helper.paginate(PaginationRequest.<TestConnection>builder()
                    .client(client)
                    .scopeId(SCOPE_ID)
                    .documentName(DOCUMENT_NAME)
                    .variables(Map.of("key", "value"))
                    .timeout(TIMEOUT)
                    .connectionFieldPath(FIELD_PATH)
                    .connectionType(TestConnection.class)
                    .pageInfoExtractor(TestConnection::getPageInfo)
                    .pageProcessorWhile(conn -> {
                        pageCount.incrementAndGet();
                        return true;
                    })
                    .contextDescription("test")
                    .maxPages(3) // Limit to 3 pages
                    .build());

            assertThat(result.pagesProcessed()).isEqualTo(3);
            assertThat(result.terminationReason()).isEqualTo(TerminationReason.MAX_PAGES_REACHED);
            assertThat(result.isAborted()).isTrue();
            assertThat(pageCount.get()).isEqualTo(3);
        }

        @Test
        void shouldUseInitialCursorWhenProvided() {
            String initialCursor = "resumeCursor123";
            GHPageInfo pageInfo = pageInfo("cursor1", false);
            TestConnection connection = new TestConnection(List.of("item1"), pageInfo);
            ClientGraphQlResponse response = mockValidResponse(connection);
            mockClientExecution(response);

            PaginationResult result = helper.paginate(PaginationRequest.<TestConnection>builder()
                    .client(client)
                    .scopeId(SCOPE_ID)
                    .documentName(DOCUMENT_NAME)
                    .variables(Map.of("key", "value"))
                    .timeout(TIMEOUT)
                    .connectionFieldPath(FIELD_PATH)
                    .connectionType(TestConnection.class)
                    .pageInfoExtractor(TestConnection::getPageInfo)
                    .pageProcessorWhile(conn -> true)
                    .contextDescription("test")
                    .initialCursor(initialCursor)
                    .build());

            assertThat(result.isComplete()).isTrue();
            // Verify the initial cursor was used
            verify(requestSpec).variable("after", initialCursor);
        }

        @Test
        void shouldTrackRateLimitForEachPage() {
            GHPageInfo pageInfo1 = pageInfo("cursor1", true);
            GHPageInfo pageInfo2 = pageInfo("cursor2", false);

            TestConnection connection1 = new TestConnection(List.of("item1"), pageInfo1);
            TestConnection connection2 = new TestConnection(List.of("item2"), pageInfo2);

            ClientGraphQlResponse response1 = mockValidResponse(connection1);
            ClientGraphQlResponse response2 = mockValidResponse(connection2);

            mockClientExecutionSequence(response1, response2);

            helper.paginate(createRequest(conn -> true));

            verify(graphQlClientProvider, times(2)).trackRateLimit(eq(SCOPE_ID), any(ClientGraphQlResponse.class));
        }
    }

    @Nested
    class BuilderTests {

        @Test
        void shouldThrowWhenRequiredFieldsAreMissing() {
            assertThatThrownBy(() -> PaginationRequest.<TestConnection>builder().build())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("client is required");
        }

        @Test
        void shouldUseDefaultMaxPagesWhenNotSpecified() {
            GHPageInfo pageInfo = pageInfo(null, false);
            TestConnection connection = new TestConnection(List.of("item"), pageInfo);
            ClientGraphQlResponse response = mockValidResponse(connection);
            mockClientExecution(response);

            PaginationRequest<TestConnection> request = createRequest(conn -> true);

            // Then - default is MAX_PAGINATION_PAGES (1000)
            assertThat(request.maxPages()).isEqualTo(GitHubSyncConstants.MAX_PAGINATION_PAGES);
        }
    }

    @Nested
    class PaginationResultTests {

        @Test
        void isCompleteShouldReturnTrueOnlyForCompleted() {
            assertThat(new PaginationResult(1, TerminationReason.COMPLETED).isComplete())
                    .isTrue();
            assertThat(new PaginationResult(1, TerminationReason.MAX_PAGES_REACHED).isComplete())
                    .isFalse();
            assertThat(new PaginationResult(1, TerminationReason.RATE_LIMIT_CRITICAL).isComplete())
                    .isFalse();
        }

        @Test
        void isAbortedShouldReturnFalseForCompletedAndProcessorStop() {
            assertThat(new PaginationResult(1, TerminationReason.COMPLETED).isAborted())
                    .isFalse();
            assertThat(new PaginationResult(1, TerminationReason.PROCESSOR_STOP).isAborted())
                    .isFalse();
            assertThat(new PaginationResult(1, TerminationReason.MAX_PAGES_REACHED).isAborted())
                    .isTrue();
            assertThat(new PaginationResult(1, TerminationReason.RATE_LIMIT_CRITICAL).isAborted())
                    .isTrue();
        }
    }

    @Nested
    class ErrorHandlingTests {

        @Test
        void shouldReturnInterruptedWhenThreadIsInterrupted() {
            // Given - set interrupt flag before paginate
            Thread.currentThread().interrupt();

            PaginationResult result = helper.paginate(createRequest(conn -> true));

            assertThat(result.terminationReason()).isEqualTo(TerminationReason.INTERRUPTED);
            assertThat(result.pagesProcessed()).isZero();
            assertThat(result.isAborted()).isTrue();
        }

        @Test
        void shouldReturnInvalidResponseWhenResponseIsNull() {
            // Given - execute() returns Mono.empty() which blocks to null
            when(client.documentName(DOCUMENT_NAME)).thenReturn(requestSpec);
            when(requestSpec.variable(any(), any())).thenReturn(requestSpec);
            when(requestSpec.execute()).thenReturn(Mono.empty());

            PaginationResult result = helper.paginate(createRequest(conn -> true));

            assertThat(result.terminationReason()).isEqualTo(TerminationReason.INVALID_RESPONSE);
            assertThat(result.pagesProcessed()).isEqualTo(1);
        }

        @Test
        void shouldTrackRateLimitEvenWhenProcessorStops() {
            // Given - processor returns false (stop) on first page
            GHPageInfo pageInfo = pageInfo("cursor1", true);
            TestConnection connection = new TestConnection(List.of("item1"), pageInfo);
            ClientGraphQlResponse response = mockValidResponse(connection);
            mockClientExecution(response);

            PaginationResult result = helper.paginate(createRequest(conn -> false));

            assertThat(result.terminationReason()).isEqualTo(TerminationReason.PROCESSOR_STOP);
            // Rate limit tracking should still have been called
            verify(graphQlClientProvider).trackRateLimit(eq(SCOPE_ID), any(ClientGraphQlResponse.class));
        }

        @AfterEach
        void clearInterruptFlag() {
            // Always clear interrupt flag to avoid contaminating other tests
            Thread.interrupted();
        }
    }

    // Helper methods

    private PaginationRequest<TestConnection> createRequest(
            GraphQlPaginationHelper.PageProcessor<TestConnection> processor) {
        return PaginationRequest.<TestConnection>builder()
                .client(client)
                .scopeId(SCOPE_ID)
                .documentName(DOCUMENT_NAME)
                .variables(Map.of("key", "value"))
                .timeout(TIMEOUT)
                .connectionFieldPath(FIELD_PATH)
                .connectionType(TestConnection.class)
                .pageInfoExtractor(TestConnection::getPageInfo)
                .pageProcessorWhile(processor)
                .contextDescription("test")
                .build();
    }

    // Shared setup helpers: stubs are lenient because not every caller exercises every stub
    // (e.g. rate-limit-critical and builder tests short-circuit before reading the connection).
    private ClientGraphQlResponse mockValidResponse(TestConnection connection) {
        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        ClientResponseField field = mock(ClientResponseField.class);
        lenient().when(response.isValid()).thenReturn(true);
        lenient().when(response.field(FIELD_PATH)).thenReturn(field);
        lenient().when(field.toEntity(TestConnection.class)).thenReturn(connection);
        return response;
    }

    private void mockClientExecution(ClientGraphQlResponse response) {
        lenient().when(client.documentName(DOCUMENT_NAME)).thenReturn(requestSpec);
        lenient().when(requestSpec.variable(any(), any())).thenReturn(requestSpec);
        lenient().when(requestSpec.execute()).thenReturn(Mono.just(response));
    }

    private void mockClientExecutionSequence(ClientGraphQlResponse... responses) {
        when(client.documentName(DOCUMENT_NAME)).thenReturn(requestSpec);
        when(requestSpec.variable(any(), any())).thenReturn(requestSpec);

        var execution = when(requestSpec.execute()).thenReturn(Mono.just(responses[0]));
        for (int i = 1; i < responses.length; i++) {
            execution = execution.thenReturn(Mono.just(responses[i]));
        }
    }

    private static GHPageInfo pageInfo(@Nullable String endCursor, boolean hasNextPage) {
        GHPageInfo pageInfo = new GHPageInfo();
        pageInfo.setHasNextPage(hasNextPage);
        if (endCursor != null) pageInfo.setEndCursor(endCursor);
        return pageInfo;
    }
}
