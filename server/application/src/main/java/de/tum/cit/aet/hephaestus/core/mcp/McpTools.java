package de.tum.cit.aet.hephaestus.core.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
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

    /** A tool's name, its title for people, and its description for the model. */
    public record Text(String name, String title, String description) {}

    /** What a write tool does to the workspace, as the MCP tool annotations state it. */
    public enum Effect {
        /** Adds something; repeating the call adds nothing more or is refused. */
        ADDS(false, false),
        /** Changes a setting; repeating the call with the same arguments changes nothing more. */
        CHANGES(false, true),
        /** Removes something that only the reverse tool brings back. */
        REMOVES(true, false),
        /** Replaces the workspace's own values with the catalog's. */
        REPLACES(true, true);

        private final boolean destructive;
        private final boolean idempotent;

        Effect(boolean destructive, boolean idempotent) {
            this.destructive = destructive;
            this.idempotent = idempotent;
        }
    }

    /**
     * A tool that changes the workspace. Its call needs {@code practices:write}, and its description says, as a read
     * tool's does, that free text in the result is configuration to treat as data.
     */
    public static McpSchema.Tool writeTool(
            JsonMapper jsonMapper, Text text, String inputSchema, String outputSchema, Effect effect) {
        var mapper = new JacksonMcpJsonMapper(jsonMapper);
        return McpSchema.Tool.builder(text.name(), mapper, inputSchema)
                .title(text.title())
                .description(text.description())
                .outputSchema(mapper, outputSchema)
                .annotations(new McpSchema.ToolAnnotations(
                        text.title(), false, effect.destructive, effect.idempotent, false, null))
                .build();
    }

    /**
     * The arguments read into the HTTP handler's own request type and validated as the handler validates its body, so
     * a tool accepts exactly what the handler accepts. A refusal names the field and its rule, as a 400 over HTTP does.
     * The mapper is one from {@link #withNulls}, so a null argument stays null.
     */
    public static <T> T input(
            JsonMapper jsonMapper, Validator validator, Map<String, Object> arguments, Class<T> type) {
        T input;
        try {
            input = jsonMapper.convertValue(arguments, type);
        } catch (JacksonException unreadable) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, unreadableAt(unreadable.getPath()), unreadable);
        } catch (IllegalArgumentException unreadable) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, unreadableAt(List.of()), unreadable);
        }
        var violations = validator.validate(input);
        if (!violations.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    violations.stream().map(McpTools::describe).sorted().collect(Collectors.joining("; ")));
        }
        return input;
    }

    /** Names where the arguments stopped matching, by field names and indexes only, never by the value or a type name. */
    private static String unreadableAt(List<JacksonException.Reference> path) {
        String at = path.stream()
                .map(reference -> reference.getPropertyName() != null
                        ? reference.getPropertyName()
                        : String.valueOf(reference.getIndex()))
                .collect(Collectors.joining("."));
        return at.isEmpty()
                ? "The arguments do not have the shape that the tool's input schema describes."
                : "The argument " + at + " does not have the shape that the tool's input schema describes.";
    }

    private static String describe(ConstraintViolation<?> violation) {
        return violation.getPropertyPath() + ": " + violation.getMessage();
    }

    /**
     * The application's mapper, but writing null values. The application leaves nulls out of its JSON, and the MCP
     * path must keep them: a null argument means "none" or "inherit", and the output schemas list nullable fields as
     * required.
     */
    public static JsonMapper withNulls(JsonMapper jsonMapper) {
        return jsonMapper
                .rebuild()
                .changeDefaultPropertyInclusion(inclusion ->
                        JsonInclude.Value.construct(JsonInclude.Include.ALWAYS, JsonInclude.Include.ALWAYS))
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
