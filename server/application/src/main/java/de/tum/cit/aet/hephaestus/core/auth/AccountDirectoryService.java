package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.event.AiChoiceChangedEvent;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** SCM and Outline capture permission checks use account state on the server role. */
@Service
@WorkspaceAgnostic("Account status and choices span all workspaces")
@RequiredArgsConstructor
public class AccountDirectoryService implements AccountDirectory {
    /** The zone ids this runtime knows. A stored id can be unknown after a time zone data update. */
    private static final Set<String> KNOWN_ZONES = Set.copyOf(ZoneId.getAvailableZoneIds());

    private final AccountRepository accounts;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountView> find(long accountId) {
        return accounts.findById(accountId).map(AccountDirectoryService::view);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, AccountView> findAll(Collection<Long> accountIds) {
        return accounts.findAllById(accountIds).stream()
                .map(AccountDirectoryService::view)
                .collect(Collectors.toUnmodifiableMap(AccountView::id, Function.identity()));
    }

    @Override
    @Transactional
    public void setFeedbackDeliveryEnabled(long accountId, boolean enabled) {
        var account = accounts.findByIdForUpdate(accountId).orElseThrow();
        if (account.getStatus() != Account.Status.ACTIVE)
            throw new IllegalStateException("Only an active account can choose feedback delivery");
        account.setFeedbackDeliveryEnabled(enabled);
        accounts.save(account);
    }

    @Override
    @Transactional
    public void setAiChoice(long accountId, @Nullable AiChoice choice) {
        var account = accounts.findByIdForUpdate(accountId).orElseThrow();
        if (account.getStatus() != Account.Status.ACTIVE)
            throw new IllegalStateException("Only an active account can choose AI use");
        account.setAiChoice(choice);
        account.setAiChoiceAt(choice == null ? null : clock.instant());
        accounts.save(account);
        events.publishEvent(new AiChoiceChangedEvent(accountId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ZoneId> timeZone(long accountId) {
        return accounts.findById(accountId)
                .map(Account::getTimeZone)
                .filter(KNOWN_ZONES::contains)
                .map(ZoneId::of);
    }

    @Override
    @Transactional
    public void setTimeZone(long accountId, @Nullable String timeZone) {
        if (timeZone != null && !KNOWN_ZONES.contains(timeZone))
            throw new IllegalArgumentException("Unknown time zone: " + timeZone);
        var account = accounts.findByIdForUpdate(accountId).orElseThrow();
        account.setTimeZone(timeZone);
        accounts.save(account);
    }

    private static AccountView view(Account account) {
        return new AccountView(
                Objects.requireNonNull(account.getId()),
                account.getStatus(),
                account.getDisplayName(),
                account.getAiChoice(),
                account.getAiChoiceAt(),
                account.isFeedbackDeliveryEnabled());
    }
}
