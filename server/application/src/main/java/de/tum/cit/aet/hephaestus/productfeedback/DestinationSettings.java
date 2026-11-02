package de.tum.cit.aet.hephaestus.productfeedback;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Reads the destination singleton; before an operator saves one, nothing is configured. */
@Component
@RequiredArgsConstructor
class DestinationSettings {
    private final ProductFeedbackDestinationRepository repository;

    ProductFeedbackDestination current() {
        return repository.findById(ProductFeedbackDestination.SINGLETON).orElseGet(ProductFeedbackDestination::new);
    }
}
