package de.tum.cit.aet.hephaestus.core.settings.spi;

import jakarta.validation.constraints.NotNull;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.NonNull;

/**
 * The instance admin's view of the public usage page: whether it is on, and each withdrawal.
 *
 * @param withdrawalsUnreadable whether a stored withdrawal could not be read. The page then hides every figure, and
 *     {@code withdrawals} lists only the ones that could be read, so the list is not a complete copy to restore from.
 */
public record PublicUsagePolicyDTO(
        @NonNull @NotNull Boolean allowed,
        @NonNull List<PublicUsageWithdrawalDTO> withdrawals,
        @NonNull @NotNull Boolean withdrawalsUnreadable) {

    public static PublicUsagePolicyDTO of(PublicUsageState state) {
        var withdrawals = state.withdrawn().stream()
                .map(PublicUsageState::parse)
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(PublicUsageState.Withdrawal::period)
                        .reversed()
                        .thenComparing(withdrawal -> Objects.toString(withdrawal.figure(), "")))
                .map(withdrawal ->
                        new PublicUsageWithdrawalDTO(withdrawal.period().key(), withdrawal.figure()))
                .toList();
        return new PublicUsagePolicyDTO(state.allowed(), withdrawals, state.unreadable());
    }
}
