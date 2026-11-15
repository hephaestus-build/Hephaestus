package de.tum.cit.aet.hephaestus.core.auth.dto;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record ProviderIdentityRefDTO(
        @NonNull String provider,
        @Nullable String login,
        @Nullable String displayName,
        @Nullable String avatarUrl) {}
