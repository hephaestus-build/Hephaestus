package de.tum.cit.aet.hephaestus.notification.push;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * The Expo push service's HTTP API: send, which answers with one ticket per message, and receipts, which
 * say later whether Apple or Google took the message. A ticket is not a delivery.
 */
@ConditionalOnServerRole
@Component
public class ExpoPushClient {

    /** Expo accepts at most 100 messages per send and 1000 ids per receipt request. */
    static final int MAX_MESSAGES = 100;

    static final int MAX_RECEIPT_IDS = 1000;

    public record Message(
            String to,
            String title,
            String body,
            Map<String, String> data,
            String sound,
            String channelId,
            String priority) {}

    public record Details(@Nullable String error) {}

    /** {@code status} is {@code ok} with an {@code id}, or {@code error} with {@code details.error}. */
    public record Ticket(
            String status,
            @Nullable String id,
            @Nullable String message,
            @Nullable Details details) {
        boolean accepted() {
            return "ok".equals(status) && id != null;
        }

        @Nullable
        String error() {
            return details == null ? null : details.error();
        }
    }

    public record Receipt(
            String status,
            @Nullable String message,
            @Nullable Details details) {
        @Nullable
        String error() {
            return details == null ? null : details.error();
        }
    }

    record TicketResponse(@Nullable List<Ticket> data) {}

    record ReceiptResponse(@Nullable Map<String, Receipt> data) {}

    record ReceiptRequest(List<String> ids) {}

    /** What one send came to. {@code retryable} rejections are released for another attempt. */
    public sealed interface SendOutcome permits Accepted, Rejected {}

    /** One ticket per message, in the order sent. */
    public record Accepted(List<Ticket> tickets) implements SendOutcome {}

    public record Rejected(String reason, boolean retryable) implements SendOutcome {}

    private final RestClient client;
    private final PushProperties properties;

    @Autowired
    ExpoPushClient(PushProperties properties) {
        this(RestClient.builder().requestFactory(requestFactory()), properties);
    }

    ExpoPushClient(RestClient.Builder builder, PushProperties properties) {
        this.properties = properties;
        this.client = builder.baseUrl(properties.expoBaseUrl().toString())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private static ClientHttpRequestFactory requestFactory() {
        return ClientHttpRequestFactoryBuilder.jdk()
                .build(HttpClientSettings.defaults()
                        .withTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(20))
                        .withRedirects(HttpRedirects.DONT_FOLLOW));
    }

    public SendOutcome send(List<Message> messages) {
        try {
            TicketResponse response = client.post()
                    .uri("/--/api/v2/push/send")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.expoAccessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(messages)
                    .retrieve()
                    .body(TicketResponse.class);
            List<Ticket> tickets = response == null ? null : response.data();
            if (tickets == null
                    || tickets.size() != messages.size()
                    || tickets.stream().anyMatch(Objects::isNull)) {
                return new Rejected("malformed_response", true);
            }
            return new Accepted(tickets);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            // 429 and 5xx are the service asking for a later retry; any other 4xx will not change.
            return new Rejected("http_" + status, status == 429 || status >= 500);
        } catch (RestClientException e) {
            return new Rejected("unavailable", true);
        }
    }

    /** Receipts by ticket id; ids Expo does not know yet are simply absent. Empty on any failure. */
    public Map<String, Receipt> receipts(List<String> ticketIds) {
        try {
            ReceiptResponse response = client.post()
                    .uri("/--/api/v2/push/getReceipts")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.expoAccessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ReceiptRequest(ticketIds))
                    .retrieve()
                    .body(ReceiptResponse.class);
            Map<String, Receipt> receipts = response == null ? null : response.data();
            return receipts == null ? Map.of() : receipts;
        } catch (RestClientException e) {
            return Map.of();
        }
    }
}
