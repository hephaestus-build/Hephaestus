package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Main's feedback text policy for the texts of one review on the work, for the composition evaluation. No case quotes a
 * link in a cited line, so no link is supplied.
 */
public final class FeedbackTextCheck {
    private FeedbackTextCheck() {}

    /** The refusal that preparation raises for the first text, by field, that breaks the policy; null when none does. */
    public static @Nullable FeedbackTextRefusedException refusal(Map<String, String> textsByField) {
        var policy = new FeedbackTextPolicy(List.of());
        for (var text : textsByField.entrySet()) {
            var rule = policy.broken(text.getValue());
            if (rule != null)
                return new FeedbackTextRefusedException(FeedbackDestination.ON_WORK, rule.name(), text.getKey());
        }
        return null;
    }
}
