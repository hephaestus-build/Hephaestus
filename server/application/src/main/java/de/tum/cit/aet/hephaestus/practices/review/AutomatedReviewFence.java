package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.practices.PracticeAutomatedReviewPolicy;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.curated.BundledPracticeCatalogLoader;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The automated-review policy a practice actually runs under.
 *
 * <p>A shipped catalogue entry that declares its evidence insufficient withdraws automated review from every
 * copy descended from it, whatever policy the copy stores: adopted copies keep their definition until an
 * administrator accepts an update, and accepting may keep the old policy field. Descent is the catalogue slug
 * the copy records, never a matching slug alone: a workspace may author its own practice under any slug.
 */
@Component
public class AutomatedReviewFence {

    private final Map<String, PracticeDefinition> withdrawnBySlug;

    @Autowired
    AutomatedReviewFence(BundledPracticeCatalogLoader bundledCatalog) {
        this(bundledCatalog.withdrawnFromAutomatedReview());
    }

    /** @param withdrawnBySlug the shipped entries that declare their evidence insufficient, by slug */
    public AutomatedReviewFence(Map<String, PracticeDefinition> withdrawnBySlug) {
        this.withdrawnBySlug = Map.copyOf(withdrawnBySlug);
    }

    public Set<String> withdrawnSlugs() {
        return withdrawnBySlug.keySet();
    }

    /** The shipped entry that withdrew this practice from automated review, or empty when its own policy decides. */
    public Optional<PracticeDefinition> withdrawal(Practice practice) {
        if (withdrawnBySlug.isEmpty()) {
            return Optional.empty();
        }
        String source = practice.getSourceCuratedSlug();
        return source == null ? Optional.empty() : Optional.ofNullable(withdrawnBySlug.get(source));
    }

    public PracticeAutomatedReviewPolicy effectivePolicy(Practice practice) {
        return withdrawal(practice)
                .map(PracticeDefinition::automatedReviewPolicy)
                .orElseGet(practice::getAutomatedReviewPolicy);
    }
}
