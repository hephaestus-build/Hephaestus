package de.tum.cit.aet.hephaestus.practices.usage;

import de.tum.cit.aet.hephaestus.core.settings.spi.PublicUsagePolicy;
import de.tum.cit.aet.hephaestus.core.settings.spi.PublicUsageState;
import de.tum.cit.aet.hephaestus.core.settings.spi.UsageFigure;
import de.tum.cit.aet.hephaestus.core.settings.spi.UsagePeriod;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Withdraws only a period that is released, so a mistyped period cannot hide a future release for good. Works while
 * the page is off too, so an admin can withdraw before turning it on.
 */
@Service
@RequiredArgsConstructor
class PublicUsageWithdrawals {

    private final UsageReleaseRepository releases;
    private final PublicUsagePolicy policy;

    @Transactional
    public PublicUsageState withdraw(String key, @Nullable UsageFigure figure) {
        var period = UsagePeriod.parse(key)
                .filter(candidate -> releases.isReleased(candidate.start()))
                .orElseThrow(() -> {
                    var exception =
                            new ResponseStatusException(HttpStatus.NOT_FOUND, "This half-year is not released.");
                    exception.getBody().setInstance(URI.create("/admin/settings/public-usage/withdrawals"));
                    return exception;
                });
        return policy.withdraw(period, figure);
    }
}
