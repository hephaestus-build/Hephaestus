package de.tum.cit.aet.hephaestus.productfeedback;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProductFeedbackRepository extends JpaRepository<ProductFeedback, UUID> {
    Page<ProductFeedback> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<ProductFeedback> findAllByResolvedAtIsNullOrderByCreatedAtDesc(Pageable pageable);

    Page<ProductFeedback> findAllByResolvedAtIsNotNullOrderByResolvedAtDesc(Pageable pageable);

    void deleteAllByWorkspaceId(Long workspaceId);

    void deleteAllByAccountId(long accountId);
}
