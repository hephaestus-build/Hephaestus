package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Coordinates the fences and durable processing restriction of a person's erasure. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class PersonDataErasureHold {

    private final IdentityRestrictionService suppression;
    private final PersonDataWriteFence writes;
    private final PersonDataCopyFence copies;

    void acquire(PersonScope person) {
        copies.holdForErasure();
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
