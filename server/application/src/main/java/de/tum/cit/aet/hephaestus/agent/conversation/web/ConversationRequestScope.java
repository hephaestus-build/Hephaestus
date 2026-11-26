package de.tum.cit.aet.hephaestus.agent.conversation.web;

import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonAct;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.function.LongFunction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transaction that reads or changes the signed-in account's conversation, with that account bound as its owner.
 */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
class ConversationRequestScope {
    private final OwnerScope owners;

    @Transactional(readOnly = true)
    public <T> T read(LongFunction<T> read) {
        return read.apply(owners.fromPrincipal(PersonAct.fromPrincipal()));
    }

    @Transactional
    public <T> T write(LongFunction<T> write) {
        return write.apply(owners.fromPrincipal(PersonAct.fromPrincipal()));
    }
}
