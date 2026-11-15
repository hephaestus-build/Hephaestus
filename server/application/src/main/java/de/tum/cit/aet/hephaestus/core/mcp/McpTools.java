package de.tum.cit.aet.hephaestus.core.mcp;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** What every workspace MCP tool needs: its definition from hand-written schemas, and its result. */
public final class McpTools {

    /** An input schema for a tool that takes no arguments. */
    public static final String NO_INPUT = """
            {"type": "object", "properties": {}, "additionalProperties": false}
            """;

    private McpTools() {}

    /**
     * A read-only tool. Its description says that free text in the result is configuration to treat as
     * data, never as instructions (ADR 0110 § Data contract).
     */
    public static McpSchema.Tool readTool(
            JsonMapper jsonMapper,
            String name,
            String title,
            String description,
            String inputSchema,
            String outputSchema) {
        var mapper = new JacksonMcpJsonMapper(jsonMapper);
        return McpSchema.Tool.builder(name, mapper, inputSchema)
                .title(title)
                .description(description)
                .outputSchema(mapper, outputSchema)
                .annotations(new McpSchema.ToolAnnotations(title, true, false, true, false, null))
                .build();
    }

    /** A result whose structured content the SDK validates against the tool's output schema. */
    public static McpSchema.CallToolResult result(Object structured) {
        return McpSchema.CallToolResult.builder().structuredContent(structured).build();
    }

    /** A required string argument; its absence is refused as the HTTP handler refuses a missing parameter. */
    public static String requiredString(Map<String, Object> arguments, String name) {
        return optionalString(arguments, name)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.BAD_REQUEST, "The argument " + name + " is required."));
    }

    public static Optional<String> optionalString(Map<String, Object> arguments, String name) {
        return arguments.get(name) instanceof String value && !value.isBlank() ? Optional.of(value) : Optional.empty();
    }

    public static int optionalInt(Map<String, Object> arguments, String name, int fallback) {
        return arguments.get(name) instanceof Number value ? value.intValue() : fallback;
    }
}
