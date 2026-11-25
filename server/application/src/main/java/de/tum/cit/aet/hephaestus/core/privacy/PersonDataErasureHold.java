package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Coordinates the fences and durable processing restriction of a person's erasure. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class PersonDataErasureHold {

    private final IdentityRestrictionService suppression;
    private final PersonDataWriteFence writes;
    private final PersonDataCopyFence copies;

    void acquire(PersonScope person) {
        if (PrivacyJournalReply.isBound()) {
            try {
                copies.holdForErasureInRequest();
            } catch (CannotAcquireLockException timeout) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "A copy is being processed. Try the erasure request again.", timeout);
            }
        } else {
            copies.holdForErasure();
        }
        writes.holdForErasure(person.identities());
    }

    void suppress(PersonScope person, UUID requestId, boolean externalCopiesRemoved) {
        suppression.suppress(person, requestId, externalCopiesRemoved);
    }

    PersonDataCopyFence.Lease erase() {
        return copies.erase();
    }

    void release(UUID requestId) {
        suppression.release(requestId);
    }
}
