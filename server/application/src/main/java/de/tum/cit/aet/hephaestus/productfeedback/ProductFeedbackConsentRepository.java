package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

@WorkspaceAgnostic("A person's public-issue choices belong to their account, not to a workspace")
interface ProductFeedbackConsentRepository extends JpaRepository<ProductFeedbackConsent, UUID> {
    Optional<ProductFeedbackConsent> findFirstByAccountIdOrderByCreatedAtDesc(long accountId);

    void deleteAllByAccountId(long accountId);
}
