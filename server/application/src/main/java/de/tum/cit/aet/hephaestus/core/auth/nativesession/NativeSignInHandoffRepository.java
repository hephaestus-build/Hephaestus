package de.tum.cit.aet.hephaestus.core.auth.nativesession;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
@WorkspaceAgnostic("Sign-in handoffs belong to an account before any workspace is chosen")
public interface NativeSignInHandoffRepository extends JpaRepository<NativeSignInHandoff, String> {

    /** Marks an unexpired code used; 1 for exactly one caller, however many race for it. */
    @Modifying
    @Query("""
        UPDATE NativeSignInHandoff h
           SET h.consumedAt = :now
         WHERE h.codeHash = :codeHash
           AND h.consumedAt IS NULL
           AND h.expiresAt > :now
        """)
    int consume(@Param("codeHash") String codeHash, @Param("now") Instant now);

    @Modifying
    @Query("DELETE FROM NativeSignInHandoff h WHERE h.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
