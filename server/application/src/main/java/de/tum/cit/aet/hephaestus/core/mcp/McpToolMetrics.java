package de.tum.cit.aet.hephaestus.core.mcp;

import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * {@code mcp.tool.calls{tool, outcome}} (ADR 0110 decision 19). Every tool and outcome registers at start, so a rule
 * that reads the failed outcome sees zero before the first failure. The tags are the fixed tool names and outcomes.
 */
final class McpToolMetrics {

    /** How a tool call ended. */
    enum Outcome {
        /** The tool did what it was asked. */
        OK,
        /** The tool refused, with the reason that its HTTP handler would give. */
        REFUSED,
        /** The tool failed with a server error, which the client sees only as a generic failure. */
        FAILED
    }

    private final Map<String, Map<Outcome, Counter>> counters;

    McpToolMetrics(MeterRegistry registry, List<McpTool> tools) {
        this.counters = tools.stream()
                .map(tool -> tool.definition().name())
                .distinct()
                .collect(Collectors.toUnmodifiableMap(name -> name, name -> {
                    Map<Outcome, Counter> byOutcome = new EnumMap<>(Outcome.class);
                    for (Outcome outcome : Outcome.values()) {
                        byOutcome.put(
                                outcome,
                                Counter.builder(CoreMetrics.MCP_TOOL_CALLS)
                                        .description("Workspace MCP tool calls by tool and outcome")
                                        .tag("tool", name)
                                        .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                                        .register(registry));
                    }
                    return byOutcome;
                }));
    }

    void record(String tool, Outcome outcome) {
        Map<Outcome, Counter> byOutcome = counters.get(tool);
        if (byOutcome != null) {
            Objects.requireNonNull(byOutcome.get(outcome)).increment();
        }
    }
}
