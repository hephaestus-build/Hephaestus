package de.tum.cit.aet.hephaestus.core.auth.webauthn;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

@WorkspaceAgnostic("Passkeys belong to accounts, not workspaces")
public interface PasskeyRecoveryCodeRepository extends JpaRepository<PasskeyRecoveryCode, String> {
    @Modifying
    @Query("DELETE FROM PasskeyRecoveryCode c WHERE c.hash = :hash AND c.account.id = :accountId")
    int consume(String hash, Long accountId);

    void deleteByAccountId(Long accountId);
}
