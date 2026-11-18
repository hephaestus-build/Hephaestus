package de.tum.cit.aet.hephaestus.core.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.mcp.McpCallGrant;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/**
 * A write tool runs only under a grant that holds {@code practices:write}, whatever the request filters let through
 * (ADR 0110 decision 14): the tool invocation checks the grant that the grant guard left on the request.
 */
@Tag("unit")
class McpToolInvocationTest {

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger uses = new AtomicInteger();
    private final McpTool write = new McpTool() {
        @Override
        public McpSchema.Tool definition() {
            return McpTools.writeTool(
                    JsonMapper.builder().build(),
                    new McpTools.Text("change_something", "Change something", "Changes something."),
                    McpTools.NO_INPUT,
                    "{\"type\": \"object\"}",
                    McpTools.Effect.CHANGES);
        }

        @Override
        public boolean writes() {
            return true;
        }

        @Override
        public McpSchema.CallToolResult call(Map<String, Object> arguments) {
            calls.incrementAndGet();
            return McpTools.result(Map.of());
        }
    };

    @AfterEach
    void clearTheRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldRefuseAWriteToolWithoutRunningItWhenTheGrantOnlyReads() {
        onRequest(new McpCallGrant(1L, UUID.randomUUID(), false, null));

        var result = invoke();

        assertThat(result.isError()).isTrue();
        assertThat(calls).hasValue(0);
        assertThat(uses).as("a refused call is not a use").hasValue(0);
    }

    @Test
    void shouldRefuseAWriteToolWithoutRunningItWhenNoGrantWasChecked() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

        var result = invoke();

        assertThat(result.isError()).isTrue();
        assertThat(calls).hasValue(0);
    }

    @Test
    void shouldRunAWriteToolWhenTheGrantMayWrite() {
        onRequest(new McpCallGrant(1L, UUID.randomUUID(), true, null));

        var result = invoke();

        assertThat(result.isError()).isNotEqualTo(true);
        assertThat(calls).hasValue(1);
        assertThat(uses).as("a call that succeeds records the grant's use").hasValue(1);
    }

    /** Spring matches an advice handler by an exception's cause, so the handler must get the cause. */
    @Test
    void shouldAnswerWithTheReasonOfTheHandlerThatMatchesTheCause() {
        onRequest(new McpCallGrant(1L, UUID.randomUUID(), true, null));
        McpTool wrapping = new McpTool() {
            @Override
            public McpSchema.Tool definition() {
                return write.definition();
            }

            @Override
            public McpSchema.CallToolResult call(Map<String, Object> arguments) {
                throw new IllegalArgumentException("wrapped", new ConflictingChange());
            }
        };
        var context = new StaticApplicationContext();
        context.registerSingleton("conflicts", ConflictAdvice.class);
        context.refresh();

        var result = McpServerConfiguration.invoke(
                wrapping,
                new McpSchema.CallToolRequest("change_something", Map.of(), Map.of()),
                new McpRefusals(context),
                new McpToolMetrics(new SimpleMeterRegistry(), List.of(wrapping)),
                uses::incrementAndGet);

        assertThat(result.isError()).isTrue();
        assertThat(((McpSchema.TextContent) result.content().getFirst()).text()).isEqualTo("Someone changed it first.");
        assertThat(uses).as("a call that its handler refuses is not a use").hasValue(0);
    }

    static final class ConflictingChange extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    @RestControllerAdvice
    static final class ConflictAdvice {
        @ExceptionHandler(ConflictingChange.class)
        ProblemDetail conflict(ConflictingChange exception) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Someone changed it first.");
        }
    }

    private McpSchema.CallToolResult invoke() {
        var context = new StaticApplicationContext();
        context.refresh();
        return McpServerConfiguration.invoke(
                write,
                new McpSchema.CallToolRequest("change_something", Map.of(), Map.of()),
                new McpRefusals(context),
                new McpToolMetrics(new SimpleMeterRegistry(), List.of(write)),
                uses::incrementAndGet);
    }

    private static void onRequest(McpCallGrant grant) {
        var request = new MockHttpServletRequest();
        request.setAttribute(McpCallGrant.ATTRIBUTE, grant);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
