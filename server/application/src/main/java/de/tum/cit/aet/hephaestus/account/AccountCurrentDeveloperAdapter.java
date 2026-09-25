package de.tum.cit.aet.hephaestus.account;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * In-{@code account}-module implementation of {@link CurrentDeveloperLookup}. The port is owned by
 * {@code practices}, which needs one fact about the caller — their developer id — without importing the
 * SCM user entity for it.
 *
 * <p>Bound here rather than next to {@code UserRepository}: {@code practices} still reaches into the SCM
 * user store from several older classes, so an adapter living there would close a {@code practices ↔
 * scm-data-platform} cycle that the module-boundary rules reject. This module already answers "what is
 * true about the signed-in account" for other modules' ports (see
 * {@link AccountPreferencesQueryAdapter}), and nothing
 * depends on it in return.
 */
@Service
public class AccountCurrentDeveloperAdapter implements CurrentDeveloperLookup {

    private final UserRepository userRepository;

    public AccountCurrentDeveloperAdapter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public Optional<Long> currentDeveloperId() {
        return userRepository.getCurrentUser().map(User::getId);
    }

    @Override
    public long currentDeveloperIdElseThrow() {
        return userRepository.getCurrentUserElseThrow().getId();
    }
}
