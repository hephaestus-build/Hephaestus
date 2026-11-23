package de.tum.cit.aet.hephaestus.agent.conversation.web;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonAct;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.UserViewContextHolder;
import java.util.function.LongFunction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The transaction that reads or changes the signed-in account's conversation. */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
class ConversationRequestScope {
    private final OwnerScope owners;

    @Transactional
    public <T> T execute(LongFunction<T> operation) {
        return operation.apply(owners.fromPrincipal(requireAct()));
    }

    static PersonAct requireAct() {
        if (UserViewContextHolder.get() != null) {
            throw new EntityNotFoundException("Conversation", "current");
        }
        return PersonAct.fromPrincipal();
    }
}
