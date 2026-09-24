package de.tum.cit.aet.hephaestus.testconfig;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;

public final class TestUserFactory {

    private TestUserFactory() {}

    public static User ensureUser(UserRepository repository, String login, long fallbackId) {
        return repository.findByLogin(login).orElseGet(() -> repository.save(createUser(fallbackId, login)));
    }

    public static User ensureUser(UserRepository repository, String login, long fallbackId, IdentityProvider provider) {
        Long providerId = provider.getId();
        assertNotNull(providerId);
        return repository
                .findByLoginAndProviderId(login, providerId)
                .orElseGet(() -> repository.save(createUser(fallbackId, login, provider)));
    }

    /** Creates an active identity during setup; disabled links are not reactivated. */
    public static void ensureAccountForUser(AccountRepository accounts, IdentityLinkRepository identities, User actor) {
        Long providerId = actor.getProvider().getId();
        assertNotNull(providerId);
        String subject = actor.getNativeId().toString();
        if (identities.findActiveByProviderSubject(providerId, subject, null).isPresent()) {
            return;
        }
        var account = new Account(actor.getLogin());
        if ("admin".equals(actor.getLogin())) {
            account.setAppRole(Account.AppRole.APP_ADMIN);
        }
        account = accounts.saveAndFlush(account);
        var link = new IdentityLink();
        link.setAccount(account);
        link.setProviderId(providerId);
        link.setSubject(subject);
        link.setUsernameAtSignup(actor.getLogin());
        link.setExternalActorId(actor.getId());
        identities.saveAndFlush(link);
    }

    public static User createUser(long id, String login) {
        User user = new User();
        user.setNativeId(id);
        user.setLogin(login);
        user.setName(login);
        user.setAvatarUrl("https://github.com/" + login + ".png");
        user.setHtmlUrl("https://github.com/" + login);
        user.setType(User.Type.USER);
        user.setEmail(login + "@example.com");
        return user;
    }

    public static User createUser(long id, String login, IdentityProvider provider) {
        User user = createUser(id, login);
        user.setProvider(provider);
        return user;
    }
}
