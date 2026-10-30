package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

@WorkspaceAgnostic("Account erasure is account-scoped; the rows it removes span every workspace")
interface AccountErasureRepository extends Repository<Account, Long> {

    @Modifying
    @Query(value = "DELETE FROM account_feature WHERE account_id = :accountId", nativeQuery = true)
    void deleteFeatures(@Param("accountId") Long accountId);

    @Modifying
    @Query(
            value = "DELETE FROM account_public_activity_hidden_by_default WHERE account_id = :accountId",
            nativeQuery = true)
    void deletePublicActivityHiddenByDefault(@Param("accountId") Long accountId);

    @Modifying
    @Query(value = "DELETE FROM identity WHERE account_id = :accountId", nativeQuery = true)
    void deleteIdentities(@Param("accountId") Long accountId);

    @Modifying
    @Query(value = "DELETE FROM client_sign_in_handoff WHERE account_id = :accountId", nativeQuery = true)
    void deleteSignInHandoffs(@Param("accountId") Long accountId);

    @Modifying
    @Query(value = "DELETE FROM client_session WHERE account_id = :accountId", nativeQuery = true)
    void deleteClientSessions(@Param("accountId") Long accountId);

    @Modifying
    @Query(value = "DELETE FROM issued_jwt WHERE account_id = :accountId", nativeQuery = true)
    void deleteIssuedTokens(@Param("accountId") Long accountId);

    @Modifying
    @Query(value = "DELETE FROM account_export WHERE account_id = :accountId", nativeQuery = true)
    void deleteExports(@Param("accountId") Long accountId);

    @Modifying
    @Query(value = "UPDATE consent_decision SET account_id = NULL WHERE account_id = :accountId", nativeQuery = true)
    void unlinkConsentDecisions(@Param("accountId") Long accountId);

    @Modifying
    @Query(value = """
            UPDATE auth_event SET ip_inet = NULL, user_agent = NULL, details = NULL,
                account_id = CASE WHEN account_id = :accountId THEN NULL ELSE account_id END,
                acting_account_id = CASE WHEN acting_account_id = :accountId THEN NULL ELSE acting_account_id END,
                viewed_actor_id = CASE WHEN viewed_account_id = :accountId THEN NULL ELSE viewed_actor_id END,
        viewed_account_id = CASE WHEN viewed_account_id = :accountId THEN NULL ELSE viewed_account_id END
             WHERE account_id = :accountId OR acting_account_id = :accountId
                OR viewed_account_id = :accountId
        """, nativeQuery = true)
    int redactAuthEvents(@Param("accountId") Long accountId);

    /** The append-only audit trigger permits nulling account references for erasure. */
    @Modifying
    @Query(value = """
        UPDATE config_audit_event
           SET actor_account_id = CASE WHEN actor_account_id = :accountId THEN NULL ELSE actor_account_id END,
               acting_account_id = CASE WHEN acting_account_id = :accountId THEN NULL ELSE acting_account_id END
         WHERE actor_account_id = :accountId OR acting_account_id = :accountId
        """, nativeQuery = true)
    int unlinkConfigAuditEvents(@Param("accountId") Long accountId);
}
