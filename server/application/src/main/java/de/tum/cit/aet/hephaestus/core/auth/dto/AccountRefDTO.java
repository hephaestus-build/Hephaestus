package de.tum.cit.aet.hephaestus.core.auth.dto;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record AccountRefDTO(
        @NonNull @Schema(pattern = "^[1-9][0-9]*$") String id,
        Account.@NonNull Status status,
        @Nullable String displayName,
        @Nullable String avatarUrl,
        @NonNull List<ProviderIdentityRefDTO> identities) {}
