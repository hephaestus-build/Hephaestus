package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The statements an account purge erases the account's auth-owned rows with. {@link AccountPurger} owns their
 * order; each statement here is one step of it.
 */
@WorkspaceAgnostic("Account erasure is account-scoped; the rows it removes span every workspace")
interface AccountErasureRepository extends Repository<Account, Long> {

    @Modifying
    @Query(value = "DELETE FROM account_feature WHERE account_id = :accountId", nativeQuery = true)
    void deleteFeatures(@Param("accountId") Long accountId);

    @Modifying
    @Query(value = "DELETE FROM identity_link WHERE account_id = :accountId", nativeQuery = true)
    void deleteIdentityLinks(@Param("accountId") Long accountId);

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
            UPDATE auth_event SET ip_inet = NULL, user_agent = NULL, details = NULL
             WHERE account_id = :accountId OR acting_account_id = :accountId
                OR viewed_user_id IN (SELECT external_actor_id FROM identity_link WHERE account_id = :accountId)
            """, nativeQuery = true)
    int redactAuthEvents(@Param("accountId") Long accountId);

    /** The append-only audit trigger permits nulling account references for erasure. */
    @Modifying
    @Query(value = """
            UPDATE config_audit_event SET actor_account_id = NULL, acting_account_id = NULL
             WHERE actor_account_id = :accountId OR acting_account_id = :accountId
            """, nativeQuery = true)
    int unlinkConfigAuditEvents(@Param("accountId") Long accountId);
}
