package de.tum.cit.aet.hephaestus.core.auth.webauthn;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

@WorkspaceAgnostic("Passkeys belong to accounts, not workspaces")
public interface PasskeyChallengeRepository extends JpaRepository<PasskeyChallenge, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "SELECT c FROM PasskeyChallenge c WHERE c.id = :id AND c.account.id = :accountId AND c.tokenId = :tokenId AND c.purpose = :purpose AND c.expiresAt > :now")
    Optional<PasskeyChallenge> consumeCandidate(UUID id, Long accountId, UUID tokenId, String purpose, Instant now);

    void deleteByAccountId(Long accountId);

    void deleteByExpiresAtBefore(Instant now);
}
