package de.tum.cit.aet.hephaestus.productfeedback;

import java.util.ArrayList;
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

    /** Puts a newly filed public issue at the head of the snapshot that drafting reads, in the caller's transaction. */
    void recordPublicIssue(long number, String title) {
        repository.findById(ProductFeedbackDestination.SINGLETON).ifPresent(destination -> {
            var entries = new ArrayList<>(Snapshot.read(destination.getSnapshot()));
            entries.addFirst(new Snapshot.Entry(number, title));
            destination.setSnapshot(Snapshot.write(entries));
        });
    }
}
