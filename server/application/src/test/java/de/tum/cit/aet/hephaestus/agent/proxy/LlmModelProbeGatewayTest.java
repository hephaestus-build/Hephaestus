package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.slf4j.MDC;
import tools.jackson.databind.ObjectMapper;

class LlmModelProbeGatewayTest extends BaseUnitTest {
    @Mock
    private LlmProxyService proxy;

    @Test
    void shouldRemoveRunCorrelationForAnInstanceProbeAndRestoreTheAdminContextWhenItFails() {
        var mapper = new ObjectMapper();
        var gateway = new LlmModelProbeGateway(proxy, mapper);
        var previous = MDC.getCopyOfContextMap();
        var caller = Map.of(
                StructuredLogKeys.RUN_ID,
                "previous-run",
                StructuredLogKeys.RUN_EPOCH,
                "2",
                StructuredLogKeys.SUBJECT_KEY,
                "previous-subject",
                StructuredLogKeys.REVIEW_ID,
                "previous-review",
                StructuredLogKeys.WORKSPACE_ID,
                "77",
                StructuredLogKeys.TRACE_ID,
                "admin-trace");
        try {
            MDC.setContextMap(caller);
            when(proxy.serve(any(), any(), isNull(), any(), any())).thenAnswer(invocation -> {
                assertThat(MDC.getCopyOfContextMap())
                        .containsExactlyInAnyOrderEntriesOf(Map.of(StructuredLogKeys.TRACE_ID, "admin-trace"));
                throw new IllegalStateException("synthetic failure");
            });
            assertThatThrownBy(() -> gateway.call(
                            UUID.randomUUID(),
                            new LlmModelResolver.ConnectionRef(UsageFundingSource.INSTANCE, 1L, 2L, null),
                            LlmApiProtocol.OPENAI_COMPLETIONS,
                            mapper.createObjectNode()))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(MDC.getCopyOfContextMap()).containsExactlyInAnyOrderEntriesOf(caller);
        } finally {
            if (previous == null) MDC.clear();
            else MDC.setContextMap(previous);
        }
    }
}
