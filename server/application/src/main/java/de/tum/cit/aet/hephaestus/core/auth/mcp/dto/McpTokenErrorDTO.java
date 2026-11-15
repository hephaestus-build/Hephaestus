package de.tum.cit.aet.hephaestus.core.auth.mcp.dto;

import org.jspecify.annotations.NonNull;

/** An error response of the token endpoint (RFC 6749 § 5.2). */
public record McpTokenErrorDTO(@NonNull String error) {}
