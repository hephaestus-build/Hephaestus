package de.tum.cit.aet.hephaestus.core.auth.jwt;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides whether a presented token is still active. The first admitted use of a refreshed browser token
 * revokes the token it replaced, in the same transaction as its own check. The contract is in
 * {@code docs/auth-architecture.md} § Browser session coordination.
 */
@ConditionalOnServerRole
@Component
@WorkspaceAgnostic("Access tokens are account-scoped, not workspace-scoped")
public class AccessTokenAdmission {

    private final IssuedJwtRepository repository;
    private final AccountRepository accountRepository;

    public AccessTokenAdmission(IssuedJwtRepository repository, AccountRepository accountRepository) {
        this.repository = repository;
        this.accountRepository = accountRepository;
    }

    /**
     * Admits {@code jti} or explains the refusal.
     *
     * @return null when the token is admitted, otherwise the refused row; a refused token that was never
     *     recorded here has an empty refusal
     */
    @Transactional
    public @Nullable Refusal admit(UUID jti, Instant now) {
        IssuedJwt token = repository.findActive(jti, now).orElse(null);
        if (token == null) {
            return new Refusal(repository.findById(jti).orElse(null));
        }
        UUID predecessor = token.getPredecessorJti();
        if (predecessor != null) {
            // Account, predecessor, successor: the same order as refresh and revocation.
            accountRepository.lockForIssuance(token.getAccountId());
            repository.lockForRotation(predecessor);
            // A refresh may have replaced this unused successor while it was being read.
            if (repository.markUsed(jti, now) == 0) {
                return new Refusal(repository.findById(jti).orElse(null));
            }
            repository.revoke(predecessor, now, IssuedJwt.RevokedReason.ROTATE);
        }
        return null;
    }

    /** @param token the refused row, or null when no row exists for the token */
    public record Refusal(@Nullable IssuedJwt token) {}
}
