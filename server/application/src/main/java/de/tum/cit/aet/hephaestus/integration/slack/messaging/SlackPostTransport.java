package de.tum.cit.aet.hephaestus.integration.slack.messaging;

import com.slack.api.Slack;
import com.slack.api.SlackConfig;
import com.slack.api.util.http.SlackHttpClient;
import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import org.jspecify.annotations.Nullable;

/** Limits each outbox admission to one Slack post at the HTTP layer. */
final class SlackPostTransport {
    private SlackPostTransport() {}

    static Slack client(SlackConfig config) {
        var http = SlackHttpClient.buildOkHttpClient(config)
                .newBuilder()
                .addInterceptor(SlackPostTransport::sendPostOnce)
                .build();
        return Slack.getInstance(config, new SlackHttpClient(http));
    }

    private static Response sendPostOnce(Interceptor.Chain chain) throws IOException {
        var request = chain.request();
        var body = request.body();
        if (body == null || !request.url().encodedPath().endsWith("/chat.postMessage")) {
            return chain.proceed(request);
        }
        // The SDK disables redirects. A one-shot body also stops HTTP follow-ups such as 503 with Retry-After: 0.
        // Only a fresh outbox attempt can authorize sending the form again.
        return chain.withRetryOnConnectionFailure(false)
                .proceed(request.newBuilder().post(oneShot(body)).build());
    }

    private static RequestBody oneShot(RequestBody body) {
        return new RequestBody() {
            @Override
            public @Nullable MediaType contentType() {
                return body.contentType();
            }

            @Override
            public long contentLength() throws IOException {
                return body.contentLength();
            }

            @Override
            public boolean isOneShot() {
                return true;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                body.writeTo(sink);
            }
        };
    }
}
