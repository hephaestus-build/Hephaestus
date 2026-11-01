package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import org.springframework.data.jpa.repository.JpaRepository;

@WorkspaceAgnostic("The instance's one product feedback destination setting")
interface ProductFeedbackDestinationRepository extends JpaRepository<ProductFeedbackDestination, Short> {}
