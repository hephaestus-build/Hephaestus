package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("unit")
class ProviderOutageTest {

    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of("upstream_stalled", null, ProviderOutage.UNREACHABLE),
                Arguments.of("upstream_paused", null, ProviderOutage.UNREACHABLE),
                Arguments.of("WebClientRequestException", null, ProviderOutage.UNREACHABLE),
                Arguments.of("ReactiveException", null, ProviderOutage.UNREACHABLE),
                Arguments.of("upstream_status", 500, ProviderOutage.UNREACHABLE),
                Arguments.of("upstream_status", 503, ProviderOutage.UNREACHABLE),
                Arguments.of("upstream_status", 408, ProviderOutage.UNREACHABLE),
                Arguments.of("upstream_status", 429, ProviderOutage.RATE_LIMITED),
                Arguments.of("upstream_status", 400, null),
                Arguments.of("upstream_status", 401, null),
                Arguments.of("upstream_status", 404, null),
                Arguments.of("upstream_status", null, null),
                Arguments.of("gateway_error", null, null),
                Arguments.of("gateway_error", 502, null),
                Arguments.of("refused_work_time", 403, null),
                Arguments.of("stream_failed", 200, null),
                Arguments.of("upstream_idle", 200, null));
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @MethodSource("failures")
    void shouldTellAnOutageFromAnAnsweredFailure(
            String kind, @Nullable Integer status, @Nullable ProviderOutage expected) {
        assertThat(ProviderOutage.of(new ConversationTurn.FailedCall(kind, status)))
                .isEqualTo(expected);
    }
}
