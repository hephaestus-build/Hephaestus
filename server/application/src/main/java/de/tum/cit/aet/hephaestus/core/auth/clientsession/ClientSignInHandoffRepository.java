package de.tum.cit.aet.hephaestus.core.auth.clientsession;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
@WorkspaceAgnostic("Sign-in handoffs belong to an account before any workspace is chosen")
public interface ClientSignInHandoffRepository extends JpaRepository<ClientSignInHandoff, String> {

    /** The account a code was issued to, read without loading the row, so the account lock can come first. */
    @Query("SELECT h.accountId FROM ClientSignInHandoff h WHERE h.codeHash = :codeHash")
    Optional<Long> findAccountId(@Param("codeHash") String codeHash);

    /** Marks an unexpired code used; 1 for exactly one caller, however many race for it. */
    @Modifying
    @Query("""
        UPDATE ClientSignInHandoff h
           SET h.consumedAt = :now
         WHERE h.codeHash = :codeHash
           AND h.consumedAt IS NULL
           AND h.expiresAt > :now
        """)
    int consume(@Param("codeHash") String codeHash, @Param("now") Instant now);

    /** Burns every pending code of an account; account-wide revocation also cancels sign-ins in flight. */
    @Modifying
    @Query("""
        UPDATE ClientSignInHandoff h
           SET h.consumedAt = :now
         WHERE h.accountId = :accountId
           AND h.consumedAt IS NULL
        """)
    int consumeAllForAccount(@Param("accountId") Long accountId, @Param("now") Instant now);

    @Modifying
    @Query("DELETE FROM ClientSignInHandoff h WHERE h.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
