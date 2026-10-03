package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.util.List;
import org.jspecify.annotations.Nullable;

public interface PersonIdentityResolver {
    record Provider(long id, String type, String serverUrl) {}

    List<Provider> providers();

    PersonScope resolve(@Nullable Long accountId, List<PersonIdentity> identities);
}
