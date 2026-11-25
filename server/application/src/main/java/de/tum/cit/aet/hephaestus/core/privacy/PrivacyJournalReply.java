package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.exception.JournalPendingException;
import de.tum.cit.aet.hephaestus.core.exception.ProblemType;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournalHttpReply;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Confirm the committed facts of this request before its controller returns success. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class PrivacyJournalReply implements PrivacyJournalHttpReply {

    private static final int MAX_FACTS_PER_REPLY = 4;
    private static final ScopedValue<Reply> RESPONSE = ScopedValue.newInstance();

    private record Reply(LinkedHashSet<UUID> facts) {}

    private final PrivacyJournalShipping shipping;

    static boolean isBound() {
        return RESPONSE.isBound();
    }

    static void committed(UUID entryId) {
        if (RESPONSE.isBound()) RESPONSE.get().facts().add(entryId);
    }

    @Override
    public @Nullable Object invoke(Callable<@Nullable Object> handler) throws Exception {
        if (RESPONSE.isBound()) return handler.call();
        var reply = new Reply(new LinkedHashSet<>());
        @Nullable Object result;
        try {
            result = ScopedValue.where(RESPONSE, reply).call(handler::call);
        } catch (ResponseStatusException problem) {
            if (ProblemType.ACCOUNT_ERASURE_ACCEPTED
                    .uri()
                    .equals(problem.getBody().getType())) {
                confirm(reply);
            }
            throw problem;
        }
        if (!(result instanceof ResponseEntity<?> response)
                || response.getStatusCode().is2xxSuccessful()) {
            confirm(reply);
        }
        return result;
    }

    private void confirm(Reply reply) {
        var facts = reply.facts().iterator();
        for (int shipped = 0; shipped < MAX_FACTS_PER_REPLY && facts.hasNext(); shipped++) {
            shipping.confirm(facts.next());
        }
        if (facts.hasNext()) throw new JournalPendingException();
    }
}
