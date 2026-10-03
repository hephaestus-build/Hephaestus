package de.tum.cit.aet.hephaestus.integration.outline.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.security.OutlineOriginPolicy;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * The document-enumeration paging contract, which is a <em>data-loss</em> contract rather than a
 * networking detail.
 *
 * <p>{@code listDocuments} / {@code listArchivedDocuments} feed the reconcile's {@code seen} set, and the
 * tombstone-by-absence sweep then deletes every mirrored document that is not in it — dropping the body,
 * the content hash and the authorship columns, irreversibly, while stamping the collection as cleanly
 * synced. A listing that quietly stops short of the truth is therefore indistinguishable, downstream, from
 * "the admin deleted those documents". So it must not be able to stop short quietly: a malformed page or an
 * exhausted page cap raises {@link OutlineApiException}, which the sync records on the collection and which
 * skips the sweep entirely.
 */
class OutlineApiClientPagingTest extends BaseUnitTest {

    private static final String SERVER_URL = "https://wiki.example.com";
    private static final String TOKEN = "ol_api_secret";
    private static final String COLLECTION_ID = "col-1";

    /** Outline's page size; the client asks for exactly this many, so a full page means "there may be more". */
    private static final int PAGE_LIMIT = 100;

    /** A full page of {@code PAGE_LIMIT} minimal documents — enough to keep the pager asking for the next one. */
    private static String fullPage(int page) {
        String rows = IntStream.range(0, PAGE_LIMIT)
                .mapToObj(i -> "{\"id\":\"doc-" + page + "-" + i + "\",\"updatedAt\":\"2026-01-01T00:00:00.000Z\"}")
                .collect(Collectors.joining(","));
        return "{\"data\":[" + rows + "]}";
    }

    /** A client whose every call answers with {@code bodyForCall.apply(callIndex)}. */
    private static OutlineApiClient clientAnswering(IntFunction<String> bodyForCall, AtomicInteger calls) {
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body(bodyForCall.apply(calls.getAndIncrement()))
                .build());
        return new OutlineApiClient(
                CircuitBreaker.ofDefaults("outlineRestApi"),
                Retry.ofDefaults("outlineRestApi"),
                WebClient.builder().exchangeFunction(exchange).build(),
                new OutlineOriginPolicy(Set.of("https://wiki.example.com")));
    }

    @Test
    void listDocuments_pagesUntilAShortPage_andReturnsEverything() {
        AtomicInteger calls = new AtomicInteger();
        OutlineApiClient client = clientAnswering(
                call -> call == 0
                        ? fullPage(0)
                        : "{\"data\":[{\"id\":\"doc-tail\",\"updatedAt\":\"2026-01-01T00:00:00.000Z\"}]}",
                calls);

        List<?> metas = client.listDocuments(SERVER_URL, TOKEN, COLLECTION_ID);

        assertThat(metas).hasSize(PAGE_LIMIT + 1);
        assertThat(calls).hasValue(2);
    }

    @Test
    void listDocuments_emptyCollection_isNotAnError() {
        AtomicInteger calls = new AtomicInteger();
        OutlineApiClient client = clientAnswering(call -> "{\"data\":[]}", calls);

        assertThat(client.listDocuments(SERVER_URL, TOKEN, COLLECTION_ID)).isEmpty();
        assertThat(calls).hasValue(1);
    }

    @Test
    void listDocuments_pageWithoutADataArray_throwsRatherThanTruncatingTheListing() {
        // A page with no data array must fail: treating it as the end of the listing would let the reconcile
        // count a truncated enumeration as clean and tombstone every document past the short read.
        AtomicInteger calls = new AtomicInteger();
        OutlineApiClient client = clientAnswering(call -> (call == 0 ? fullPage(0) : "{}"), calls);

        assertThatThrownBy(() -> client.listDocuments(SERVER_URL, TOKEN, COLLECTION_ID))
                .isInstanceOf(OutlineApiException.class)
                .hasMessageContaining("documents.list")
                .hasMessageContaining(COLLECTION_ID);
    }

    @Test
    void listArchivedDocuments_pageWithoutADataArray_throwsRatherThanTruncatingTheListing() {
        AtomicInteger calls = new AtomicInteger();
        OutlineApiClient client = clientAnswering(call -> "{}", calls);

        assertThatThrownBy(() -> client.listArchivedDocuments(SERVER_URL, TOKEN, COLLECTION_ID))
                .isInstanceOf(OutlineApiException.class)
                .hasMessageContaining("archived");
    }

    @Test
    void listDocuments_pageCapExhausted_throwsRatherThanReturningATruncatedListing() {
        // Every page comes back full, so the pager never sees an end. Hitting the safety cap means the
        // listing is, by construction, incomplete — the one thing the tombstone sweep must never be fed.
        AtomicInteger calls = new AtomicInteger();
        OutlineApiClient client = clientAnswering(OutlineApiClientPagingTest::fullPage, calls);

        assertThatThrownBy(() -> client.listDocuments(SERVER_URL, TOKEN, COLLECTION_ID))
                .isInstanceOf(OutlineApiException.class)
                .hasMessageContaining("page cap");
    }
}
