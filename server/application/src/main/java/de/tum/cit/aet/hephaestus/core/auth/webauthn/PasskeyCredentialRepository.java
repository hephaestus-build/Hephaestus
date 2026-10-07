package de.tum.cit.aet.hephaestus.core.auth.webauthn;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

@WorkspaceAgnostic("Passkeys belong to accounts, not workspaces")
public interface PasskeyCredentialRepository extends JpaRepository<PasskeyCredential, String> {
    List<PasskeyCredential> findByAccountId(Long accountId);

    void deleteByAccountId(Long accountId);
}
