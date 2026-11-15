package de.tum.cit.aet.hephaestus.core.auth.mcp;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** The scopes of the workspace MCP endpoint (ADR 0110 decision 14). */
public enum McpScope {
    READ("practices:read"),
    /** Implies {@link #READ}. */
    WRITE("practices:write");

    private final String value;

    McpScope(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean implies(McpScope other) {
        return this == other || (this == WRITE && other == READ);
    }

    static Optional<McpScope> of(String value) {
        return Arrays.stream(values()).filter(scope -> scope.value.equals(value)).findFirst();
    }

    /** The known scopes in a space-separated list; an unknown one is not silently dropped by callers that check. */
    static List<McpScope> parse(String scopes) {
        return Arrays.stream(scopes.trim().split(" +"))
                .filter(token -> !token.isEmpty())
                .map(McpScope::of)
                .flatMap(Optional::stream)
                .distinct()
                .toList();
    }

    static String format(List<McpScope> scopes) {
        return scopes.stream().map(McpScope::value).collect(Collectors.joining(" "));
    }
}
