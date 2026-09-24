package de.tum.cit.aet.hephaestus.account;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountPreferencesQuery;
import de.tum.cit.aet.hephaestus.workspace.CurrentAccountUsers;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@WorkspaceAgnostic("User-scoped account preferences are not workspace-specific")
public class AccountPreferencesQueryAdapter implements AccountPreferencesQuery {

    private final UserPreferencesRepository userPreferencesRepository;

    private final CurrentAccountUsers accountUsers;

    public AccountPreferencesQueryAdapter(
            UserPreferencesRepository userPreferencesRepository, CurrentAccountUsers accountUsers) {
        this.userPreferencesRepository = userPreferencesRepository;
        this.accountUsers = accountUsers;
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
}
