package de.tum.cit.aet.hephaestus.core.auth.mcp.dto;

import org.jspecify.annotations.NonNull;

/** Where the browser goes next: the client's redirect, with the code or the refusal. */
public record McpConsentRedirectDTO(@NonNull String redirectUri) {}
