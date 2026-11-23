package de.tum.cit.aet.hephaestus.core.privacy.spi;

import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import java.util.UUID;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The account and session that authorized a request to act on personal data. */
@Getter
@Accessors(fluent = true)
public final class PersonAct {
    public enum Actor {
        SELF,
        DELEGATE
    }

    public enum Client {
        BROWSER,
        INSTALLED
    }

    private final long account;
    private final Actor actor;
    private final Client client;
    private final UUID session;

    PersonAct(long account, Actor actor, Client client, UUID session) {
        this.account = account;
        this.actor = actor;
        this.client = client;
        this.session = session;
    }

    public static PersonAct fromPrincipal() {
        long account = CurrentAccount.requireId();
        UUID session = CurrentAccount.sessionIdOrNull();
        return session == null
                ? new PersonAct(account, Actor.SELF, Client.BROWSER, CurrentAccount.requireJti())
                : new PersonAct(account, Actor.SELF, Client.INSTALLED, session);
    }

    public long requireSelf() {
        if (actor != Actor.SELF) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only act on your own records.");
        }
        return account;
    }
}
