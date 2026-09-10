package de.tum.cit.aet.hephaestus.core.auth.spi;

import java.util.Optional;

/** Contact is never identity proof or a membership lookup key. */
public interface AccountContactQuery {
    Optional<String> verifiedEmail(Long accountId);
}
