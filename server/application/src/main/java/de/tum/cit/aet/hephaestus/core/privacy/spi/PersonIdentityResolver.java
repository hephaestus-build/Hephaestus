package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.util.List;
import org.jspecify.annotations.Nullable;

public interface PersonIdentityResolver {
    PersonScope resolve(@Nullable Long accountId, List<PersonIdentity> identities);
}
