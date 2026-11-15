package de.tum.cit.aet.hephaestus.core.auth.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

/** A successful token response (RFC 6749 § 5.1). */
public record McpTokenResponseDTO(
        @NonNull @JsonProperty("access_token") String accessToken,
        @NonNull @JsonProperty("token_type") String tokenType,

        @JsonProperty("expires_in") @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long expiresIn,

        @NonNull @JsonProperty("refresh_token") String refreshToken,
        @NonNull String scope) {}
