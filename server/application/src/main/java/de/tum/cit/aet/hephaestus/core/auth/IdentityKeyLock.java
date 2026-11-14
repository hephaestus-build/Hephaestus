package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Serializes exact identity ownership before account rows are locked. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class IdentityKeyLock {
    private final IdentityRepository identityRepository;
    private final PersonDataWriteFence writeFence;

    @Transactional(propagation = Propagation.MANDATORY)
    public void holdAndLock(List<PersonIdentity> identities) {
        if (!writeFence.holdForWrite(identities))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This identity is being erased.");
        identityRepository.lockOwnership(
                identities.stream().map(PersonIdentity::providerId).toArray(Long[]::new),
                identities.stream().map(PersonIdentity::subject).toArray(String[]::new),
                identities.stream().map(PersonIdentity::teamKey).toArray(String[]::new));
    }
}
