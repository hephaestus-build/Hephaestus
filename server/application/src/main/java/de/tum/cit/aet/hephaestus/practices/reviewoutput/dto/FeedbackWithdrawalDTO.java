package de.tum.cit.aet.hephaestus.practices.reviewoutput.dto;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountSummaryQuery.AccountSummary;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackWithdrawal;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "One withdrawal of a card from a developer's practice page, and its restoration if any")
public record FeedbackWithdrawalDTO(
        @NonNull UUID id,
        @NonNull String reason,
        @NonNull Instant withdrawnAt,

        @Schema(description = "Who withdrew it; null once that account is erased") @Nullable
        String withdrawnBy,

        @Schema(description = "Why it was restored; null while the withdrawal is in force") @Nullable
        String restorationReason,

        @Nullable Instant restoredAt,

        @Schema(description = "Who restored it; null while in force or once that account is erased") @Nullable
        String restoredBy) {
    public static FeedbackWithdrawalDTO from(FeedbackWithdrawal withdrawal, Map<Long, AccountSummary> accounts) {
        Long restoredBy = withdrawal.getRestoredByAccountId();
        return new FeedbackWithdrawalDTO(
                withdrawal.getId(),
                withdrawal.getReason(),
                withdrawal.getWithdrawnAt(),
                nameOf(accounts.get(withdrawal.getWithdrawnByAccountId())),
                withdrawal.getRestorationReason(),
                withdrawal.getRestoredAt(),
                restoredBy == null ? null : nameOf(accounts.get(restoredBy)));
    }

    private static @Nullable String nameOf(@Nullable AccountSummary account) {
        return account == null ? null : account.displayName();
    }
}
