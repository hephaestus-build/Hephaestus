package de.tum.cit.aet.hephaestus.account;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountPreferencesQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountTimeZone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.workspace.CurrentAccountUsers;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@WorkspaceAgnostic("User-scoped account preferences are not workspace-specific")
public class AccountPreferencesQueryAdapter implements AccountPreferencesQuery {

    private final UserPreferencesRepository userPreferencesRepository;

    private final CurrentAccountUsers accountUsers;

    private final UserRepository users;

    private final AccountIdentityQuery identities;

    private final AccountTimeZone accountTimeZone;

    public AccountPreferencesQueryAdapter(
            UserPreferencesRepository userPreferencesRepository,
            CurrentAccountUsers accountUsers,
            UserRepository users,
            AccountIdentityQuery identities,
            AccountTimeZone accountTimeZone) {
        this.userPreferencesRepository = userPreferencesRepository;
        this.accountUsers = accountUsers;
        this.users = users;
        this.identities = identities;
        this.accountTimeZone = accountTimeZone;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PreferencesView> preferencesForAccount(Long accountId) {
        return accountUsers.resolve(accountId).stream()
                .findFirst()
                .flatMap(user -> userPreferencesRepository.findByUserId(Objects.requireNonNull(user.getId())))
                .map(p -> new PreferencesView(p.isParticipateInResearch(), p.isPracticeFeedbackDeliveryEnabled()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PreferencesView> preferencesForUserId(long userId) {
        return userPreferencesRepository
                .findByUserId(userId)
                .map(p -> new PreferencesView(p.isParticipateInResearch(), p.isPracticeFeedbackDeliveryEnabled()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ZoneId> timeZoneForUserId(long userId) {
        return users.findById(userId)
                .flatMap(user -> identities.resolveAccountId(
                        Objects.requireNonNull(user.getProvider().getId()),
                        user.getNativeId().toString(),
                        null))
                .flatMap(this::timeZoneForAccount);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ZoneId> timeZoneForAccount(long accountId) {
        return accountTimeZone.timeZone(accountId).flatMap(IanaTimeZone.Validator::parse);
    }
}
