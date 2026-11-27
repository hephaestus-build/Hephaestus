package de.tum.cit.aet.hephaestus.integration.slack.messaging;

import com.slack.api.Slack;
import com.slack.api.SlackConfig;
import com.slack.api.util.http.SlackHttpClient;
import de.tum.cit.aet.hephaestus.integration.slack.SlackProperties;
import java.io.IOException;
import java.net.ProtocolException;
import java.util.function.BooleanSupplier;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import org.jspecify.annotations.Nullable;

/** Admits a Slack post after connection setup, just before OkHttp writes the request. */
final class SlackSendFence implements Interceptor {

    /** The synchronous Slack SDK runs its network interceptor on the calling thread. */
    static final ThreadLocal<@Nullable Admission> ADMISSION = new ThreadLocal<>();

    private static final SlackSendFence INSTANCE = new SlackSendFence();

    private SlackSendFence() {}

    /**
     * The configuration of this gateway's own Slack client, with a deadline for each HTTP call
     * ({@link SlackMessageService#NATIVE_CALL_TIMEOUT_MS}) and the methods under the API origin of {@code properties}. The SDK's shared
     * default client and its configuration stay unchanged.
     */
    static SlackConfig nativeConfig(SlackProperties properties) {
        SlackConfig config = new SlackConfig();
        config.setHttpClientCallTimeoutMillis(SlackMessageService.NATIVE_CALL_TIMEOUT_MS);
        config.setMethodsEndpointUrlPrefix(properties.apiBase() + "/api/");
        // SDK statistics can issue auth.test before a method call. An admitted post must make only its own request.
        config.getMethodsConfig().setStatsEnabled(false);
        return config;
    }

    static Slack client(SlackConfig config) {
        var http = SlackHttpClient.buildOkHttpClient(config)
                .newBuilder()
                .addInterceptor(SlackSendFence::sendPostOnce)
                .addNetworkInterceptor(INSTANCE)
                .build();
        return Slack.getInstance(config, new SlackHttpClient(http));
    }

    private static Response sendPostOnce(Interceptor.Chain chain) throws IOException {
        var request = chain.request();
        var body = request.body();
        if (body == null || !isPostMessage(request)) {
            return chain.proceed(request);
        }
        // The SDK disables redirects. A one-shot body also stops HTTP follow-ups such as 503 with Retry-After: 0.
        // Only a fresh outbox attempt can authorize sending the form again.
        return chain.withRetryOnConnectionFailure(false)
                .proceed(request.newBuilder().post(oneShot(body)).build());
    }

    static final class Admission {
        private final BooleanSupplier admit;
        private boolean asked;

        Admission(BooleanSupplier admit) {
            this.admit = admit;
        }
    }

    static final class NotAdmitted extends ProtocolException {
        private static final long serialVersionUID = 1L;

        NotAdmitted() {
            super("The post was not admitted at its send boundary");
        }
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        var request = chain.request();
        if (!isPostMessage(request)) {
            return chain.proceed(request);
        }
        Admission admission = ADMISSION.get();
        if (admission == null) throw new NotAdmitted();
        if (admission.asked) throw new ProtocolException("An admitted post is sent once");
        admission.asked = true;
        if (!admission.admit.getAsBoolean()) throw new NotAdmitted();
        return chain.proceed(request);
    }

    private static boolean isPostMessage(Request request) {
        return request.url().pathSegments().getLast().equals("chat.postMessage");
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
