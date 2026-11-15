package de.tum.cit.aet.hephaestus.core.auth.mcp.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** The account's answer to a consent request. */
public record McpConsentDecisionDTO(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean approve) {}
