package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Main's feedback text policy for the texts of one review, for the composition evaluation. The case's cited quotes
 * supply links and counts, as the admitted results' quotes do in preparation.
 */
public final class FeedbackTextCheck {
    private FeedbackTextCheck() {}

    /**
     * The refusal that preparation raises for the first text, by field, that breaks the policy; null when none does.
     * {@code verified} holds the counts that the server computed from the review's own rows.
     */
    public static @Nullable FeedbackTextRefusedException refusal(
            Map<String, String> textsByField, Collection<String> quotes, Set<String> verified) {
        var policy = FeedbackTextPolicy.suppliedBy(quotes);
        for (var text : textsByField.entrySet()) {
            var rule = policy.broken(text.getValue(), verified);
            if (rule != null)
                return new FeedbackTextRefusedException(FeedbackDestination.ON_WORK, rule.name(), text.getKey());
        }
        return null;
    }
}
