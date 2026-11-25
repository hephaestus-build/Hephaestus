package de.tum.cit.aet.hephaestus.integration.slack.messaging;

import com.slack.api.Slack;
import com.slack.api.SlackConfig;
import com.slack.api.util.http.SlackHttpClient;
import java.io.IOException;
import java.net.ProtocolException;
import java.util.function.BooleanSupplier;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.jspecify.annotations.Nullable;

/**
 * The admission of a {@code chat.postMessage} at the raw HTTP write, as the network interceptor of the gateway's own
 * client. It runs after the SDK's own calls, such as the {@code auth.test} that finds the team of a token it has not
 * seen, and right before the request goes out. A request that the HTTP client sends again by itself, as a follow-up to a
 * 408 or a 503, is refused: the caller admits again on its next attempt.
 */
final class SlackSendFence implements Interceptor {

    /**
     * The admission of the post that runs on this thread, set only for its call: the SDK's synchronous calls run the
     * HTTP client's interceptors on the calling thread.
     */
    static final ThreadLocal<@Nullable Admission> ADMISSION = new ThreadLocal<>();

    static final SlackSendFence INSTANCE = new SlackSendFence();

    private static final String POST_MESSAGE = "/chat.postMessage";

    private SlackSendFence() {}

    /** The gateway's own Slack client for {@code config}, whose HTTP client runs this fence. */
    static Slack client(SlackConfig config) {
        OkHttpClient http = SlackHttpClient.buildOkHttpClient(config)
                .newBuilder()
                .addNetworkInterceptor(INSTANCE)
                .build();
        SlackHttpClient client = new SlackHttpClient(http);
        client.setConfig(config);
        return Slack.getInstance(config, client);
    }

    /** One post's admission: asked once, at its first network attempt. */
    static final class Admission {
        private final BooleanSupplier admit;
        private boolean asked;

        Admission(BooleanSupplier admit) {
            this.admit = admit;
        }
    }

    /** The admission refused the post: nothing was sent. */
    static final class NotAdmitted extends ProtocolException {
        private static final long serialVersionUID = 1L;

        NotAdmitted() {
            super("The post was not admitted at its send boundary");
        }
    }

    /** The HTTP client would send an admitted post again by itself; the caller tries again later instead. */
    static final class Replay extends ProtocolException {
        private static final long serialVersionUID = 1L;

        Replay() {
            super("An admitted post is sent once");
        }
    }

    // A ProtocolException is not one that the HTTP client recovers from by retrying, so neither refusal is retried.
    @Override
    public Response intercept(Chain chain) throws IOException {
        Admission admission = ADMISSION.get();
        if (admission == null || !chain.request().url().encodedPath().endsWith(POST_MESSAGE)) {
            return chain.proceed(chain.request());
        }
        if (admission.asked) throw new Replay();
        admission.asked = true;
        if (!admission.admit.getAsBoolean()) throw new NotAdmitted();
        return chain.proceed(chain.request());
    }
}
