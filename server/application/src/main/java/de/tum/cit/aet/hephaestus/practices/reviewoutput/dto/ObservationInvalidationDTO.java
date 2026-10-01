package de.tum.cit.aet.hephaestus.practices.reviewoutput.dto;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountSummaryQuery.AccountSummary;
import de.tum.cit.aet.hephaestus.practices.model.ObservationInvalidation;
import de.tum.cit.aet.hephaestus.practices.model.ObservationInvalidation.ProviderCopy;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "One correction of an observation by a workspace admin, and its restoration if any")
public record ObservationInvalidationDTO(
        @NonNull UUID id,
        @NonNull String reason,
        @NonNull Instant invalidatedAt,

        @Schema(description = "Who invalidated it; null once that account is erased") @Nullable
        String invalidatedBy,

        @Schema(description = "Why it was restored; null while the invalidation is in force") @Nullable
        String restorationReason,

        @Nullable Instant restoredAt,

        @Schema(description = "Who restored it; null while in force or once that account is erased") @Nullable
        String restoredBy,

        @NonNull @Schema(description = "What became of the comments Hephaestus had already posted on the provider")
        ProviderCopy providerCopy) {
    public static ObservationInvalidationDTO from(
            ObservationInvalidation invalidation, Map<Long, AccountSummary> accounts) {
        Long restoredBy = invalidation.getRestoredByAccountId();
        return new ObservationInvalidationDTO(
                invalidation.getId(),
                invalidation.getReason(),
                invalidation.getInvalidatedAt(),
                nameOf(accounts.get(invalidation.getInvalidatedByAccountId())),
                invalidation.getRestorationReason(),
                invalidation.getRestoredAt(),
                restoredBy == null ? null : nameOf(accounts.get(restoredBy)),
                invalidation.getProviderCopy());
    }

    private static @Nullable String nameOf(@Nullable AccountSummary account) {
        return account == null ? null : account.displayName();
    }
}
