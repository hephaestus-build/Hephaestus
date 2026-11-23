package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.util.UUID;

public final class PersonActFixture {
    private PersonActFixture() {}

    public static PersonAct self(long account) {
        return new PersonAct(account, PersonAct.Actor.SELF, PersonAct.Client.BROWSER, UUID.randomUUID());
    }

    public static PersonAct delegate(long account) {
        return new PersonAct(account, PersonAct.Actor.DELEGATE, PersonAct.Client.BROWSER, UUID.randomUUID());
    }
}
