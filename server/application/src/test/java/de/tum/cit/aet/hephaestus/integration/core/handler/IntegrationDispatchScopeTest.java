package de.tum.cit.aet.hephaestus.integration.core.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@Tag("unit")
class IntegrationDispatchScopeTest {

    private @Nullable Map<String, String> previousMdc;

    @BeforeEach
    void saveContext() {
        previousMdc = MDC.getCopyOfContextMap();
        MDC.clear();
    }

    @AfterEach
    void restoreContext() {
        if (previousMdc == null) MDC.clear();
        else MDC.setContextMap(previousMdc);
    }

    @Test
    void shouldSetTheWorkspaceDuringDispatchAndRemoveItAfterwards() {
        MDC.put("traceId", "receiver-trace");

        String result = new IntegrationDispatchScope(42L).call(() -> {
            assertThat(MDC.get("workspace.id")).isEqualTo("42");
            assertThat(MDC.get("traceId")).isEqualTo("receiver-trace");
            return "handled";
        });

        assertThat(result).isEqualTo("handled");
        assertThat(MDC.get("workspace.id")).isNull();
        assertThat(MDC.get("traceId")).isEqualTo("receiver-trace");
        assertThat(IntegrationDispatchScope.current()).isEmpty();
    }

    @Test
    void shouldRestoreTheOuterWorkspaceWhenANestedDispatchEnds() {
        MDC.put("workspace.id", "caller-workspace");
        var outer = new IntegrationDispatchScope(42L);

        outer.call(() -> {
            new IntegrationDispatchScope(73L).call(() -> {
                assertThat(MDC.get("workspace.id")).isEqualTo("73");
                return null;
            });
            assertThat(MDC.get("workspace.id")).isEqualTo("42");
            assertThat(IntegrationDispatchScope.current()).contains(outer);
            return null;
        });

        assertThat(MDC.get("workspace.id")).isEqualTo("caller-workspace");
        assertThat(IntegrationDispatchScope.current()).isEmpty();
    }

    @Test
    void shouldClearTheWorkspaceDuringInstallationDispatchAndRestoreItAfterwards() {
        MDC.put("workspace.id", "caller-workspace");

        new IntegrationDispatchScope(null).call(() -> {
            assertThat(MDC.get("workspace.id")).isNull();
            return null;
        });

        assertThat(MDC.get("workspace.id")).isEqualTo("caller-workspace");
    }

    @Test
    void shouldRestoreTheWorkspaceWhenDispatchThrows() {
        MDC.put("workspace.id", "caller-workspace");

        assertThatThrownBy(() -> new IntegrationDispatchScope(42L).call(() -> {
                    assertThat(MDC.get("workspace.id")).isEqualTo("42");
                    throw new IllegalStateException("handler failed");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get("workspace.id")).isEqualTo("caller-workspace");
        assertThat(IntegrationDispatchScope.current()).isEmpty();
    }
}
