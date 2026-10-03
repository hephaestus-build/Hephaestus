package de.tum.cit.aet.hephaestus.integration.core.egress;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.graphql.client.ClientGraphQlRequest;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.GraphQlClientInterceptor;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

class SilentModeGraphQlInterceptorTest extends BaseUnitTest {

    @Mock
    private OutboundEgressGuard egressGuard;

    @Mock
    private GraphQlClientInterceptor.Chain chain;

    @Test
    void shouldCheckMutationWhenExecuted() {
        ClientGraphQlRequest request = request("mutation UpdateThing { updateThing { id } }");
        when(request.getOperationName()).thenReturn("UpdateThing");
        SilentModeGraphQlInterceptor interceptor = new SilentModeGraphQlInterceptor(egressGuard);
        when(chain.next(request)).thenReturn(httpExchange(interceptor, Mono.just(mock(ClientResponse.class))));

        interceptor.intercept(request, chain).block();

        verify(egressGuard).requireDeliveryAllowed("scm.graphql.UpdateThing");
    }

    @Test
    void shouldNotReachTransportWhenMutationIsSuppressed() {
        ClientGraphQlRequest request = request("mutation UpdateThing { updateThing { id } }");
        when(request.getOperationName()).thenReturn("UpdateThing");
        doThrow(new OutboundEgressSuppressedException("test"))
                .when(egressGuard)
                .requireDeliveryAllowed("scm.graphql.UpdateThing");
        SilentModeGraphQlInterceptor interceptor = new SilentModeGraphQlInterceptor(egressGuard);
        ExchangeFunction transport = mock(ExchangeFunction.class);
        when(chain.next(request)).thenReturn(httpExchange(interceptor, transport));

        assertThatThrownBy(() -> interceptor.intercept(request, chain).block())
                .isInstanceOf(OutboundEgressSuppressedException.class);
        verify(transport, never()).exchange(any());
    }

    @Test
    void shouldStopQueuedRetryWhenSilentModeEngages() {
        ClientGraphQlRequest request = request("mutation UpdateThing { updateThing { id } }");
        when(request.getOperationName()).thenReturn("UpdateThing");
        doNothing()
                .doThrow(new OutboundEgressSuppressedException("test"))
                .when(egressGuard)
                .requireDeliveryAllowed("scm.graphql.UpdateThing");
        SilentModeGraphQlInterceptor interceptor = new SilentModeGraphQlInterceptor(egressGuard);
        ExchangeFunction transport = mock(ExchangeFunction.class);
        when(transport.exchange(any())).thenReturn(Mono.error(new IllegalStateException()));
        when(chain.next(request))
                .thenReturn(httpExchange(interceptor, transport).retry(1));

        assertThatThrownBy(() -> interceptor.intercept(request, chain).block())
                .isInstanceOf(OutboundEgressSuppressedException.class);
        verify(egressGuard, times(2)).requireDeliveryAllowed("scm.graphql.UpdateThing");
        verify(transport).exchange(any());
    }

    @Test
    void shouldAllowQueryWhenDeliveryBrakeIsEngaged() {
        ClientGraphQlRequest request = request("query ReadThing { thing { id } }");
        SilentModeGraphQlInterceptor interceptor = new SilentModeGraphQlInterceptor(egressGuard);
        when(chain.next(request)).thenReturn(httpExchange(interceptor, Mono.just(mock(ClientResponse.class))));

        interceptor.intercept(request, chain).block();

        verify(egressGuard, never()).requireDeliveryAllowed(anyString());
    }

    @Test
    void shouldAllowSelectedQueryFromMultiOperationDocument() {
        ClientGraphQlRequest request =
                request("query ReadThing { thing { id } } mutation UpdateThing { updateThing { id } }");
        when(request.getOperationName()).thenReturn("ReadThing");
        SilentModeGraphQlInterceptor interceptor = new SilentModeGraphQlInterceptor(egressGuard);
        when(chain.next(request)).thenReturn(httpExchange(interceptor, Mono.just(mock(ClientResponse.class))));

        interceptor.intercept(request, chain).block();

        verify(egressGuard, never()).requireDeliveryAllowed(anyString());
    }

    private static ClientGraphQlRequest request(String document) {
        ClientGraphQlRequest request = mock(ClientGraphQlRequest.class);
        when(request.getDocument()).thenReturn(document);
        return request;
    }

    private static Mono<ClientGraphQlResponse> httpExchange(
            SilentModeGraphQlInterceptor interceptor, Mono<ClientResponse> response) {
        return httpExchange(interceptor, ignored -> response);
    }

    private static Mono<ClientGraphQlResponse> httpExchange(
            SilentModeGraphQlInterceptor interceptor, ExchangeFunction transport) {
        ClientRequest request = ClientRequest.create(HttpMethod.POST, URI.create("https://example.test/graphql"))
                .build();
        return interceptor.httpAttemptFilter().filter(request, transport).thenReturn(mock(ClientGraphQlResponse.class));
    }
}
