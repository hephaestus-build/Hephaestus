package de.tum.cit.aet.hephaestus.productfeedback;

import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Reads the destination singleton; before an operator saves one, nothing is configured. */
@Component
@RequiredArgsConstructor
public class DestinationSettings {
    private final ProductFeedbackDestinationRepository repository;

    /** The instance's private destination cannot be a workspace source. */
    public boolean excludesRepository(String nameWithOwner) {
        return nameWithOwner.equalsIgnoreCase(current().getPrivateRepository());
    }

    ProductFeedbackDestination current() {
        return repository.findById(ProductFeedbackDestination.SINGLETON).orElseGet(ProductFeedbackDestination::new);
    }

    /** Serializes draft quota admission with operator changes to the singleton. */
    ProductFeedbackDestination locked() {
        return repository
                .findForUpdate(ProductFeedbackDestination.SINGLETON)
                .orElseGet(ProductFeedbackDestination::new);
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
