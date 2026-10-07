package de.tum.cit.aet.hephaestus.core.auth.webauthn;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class PasskeyChallengeStore {
    private final PasskeyChallengeRepository challenges;
    private final Clock clock;
    /** Consumption commits even when the subsequent assertion is invalid. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String consume(UUID id, Long accountId, UUID tokenId, String purpose) {
        PasskeyChallenge challenge = challenges
                .consumeCandidate(id, accountId, tokenId, purpose, clock.instant())
                .orElseThrow(PasskeyRequiredException::new);
        String options = challenge.getOptionsJson();
        challenges.delete(challenge);
        challenges.flush();
        return options;
    }
}
