package de.tum.cit.aet.hephaestus.productfeedback;

import java.util.EnumMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Decides where a report goes. Code decides; the model's draft is only an input. A report becomes public only when
 * every condition holds. Any other report becomes a private issue when a private repository is set, and otherwise goes
 * to the admin email. The first failing condition names the reason, in the order of {@link FilingReason}.
 */
final class RoutingGate {
    private RoutingGate() {}

    /**
     * @param draft the validated draft of the exact text that would go out, or null when there is none
     * @param blockingRedaction redaction found a secret, an email address or a known name in this text
     * @param latched an earlier revision of this report set the risk latch
     */
    record Inputs(
            boolean allowPublic,
            ProductFeedback.Kind kind,
            boolean publicRepository,
            boolean privateRepository,
            @Nullable Draft draft,
            boolean blockingRedaction,
            boolean latched) {}

    record Decision(FilingRoute route, FilingReason reason, boolean latch) {}

    static Decision decide(Inputs in) {
        Draft draft = in.draft();
        boolean latch = in.latched()
                || in.blockingRedaction()
                || (draft != null && (draft.disposition() == Draft.Disposition.SECURITY || draft.aboutNamedPerson()));
        FilingReason reason = reason(in, draft);
        if (reason == FilingReason.NONE) return new Decision(FilingRoute.PUBLIC, reason, latch);
        return new Decision(in.privateRepository() ? FilingRoute.PRIVATE : FilingRoute.EMAIL, reason, latch);
    }

    private static FilingReason reason(Inputs in, @Nullable Draft draft) {
        if (!in.allowPublic() || !in.publicRepository()) return FilingReason.OPTED_OUT;
        if (in.kind() == ProductFeedback.Kind.FEEDBACK) return FilingReason.LOW_CONFIDENCE;
        if (draft != null && draft.disposition() == Draft.Disposition.SECURITY) return FilingReason.SECURITY;
        if (in.blockingRedaction()) return FilingReason.REDACTION;
        if (draft == null) return FilingReason.NO_DRAFT;
        if (draft.aboutNamedPerson()) return FilingReason.ABOUT_PERSON;
        if (in.latched()) return FilingReason.REDACTION;
        if (draft.abuse()) return FilingReason.ABUSE;
        if (draft.disposition() != Draft.Disposition.ISSUE
                || !draft.coherent()
                || draft.confidence() != Draft.Confidence.HIGH) return FilingReason.LOW_CONFIDENCE;
        return FilingReason.NONE;
    }

    /**
     * A maintainer's {@code /publish} overrides only the quality conditions: the kind and the confidence. It never
     * overrides consent, security, the risk latch or abuse, and it needs a draft.
     */
    static @Nullable FilingReason refusePromotion(boolean allowPublic, @Nullable Draft draft, boolean latched) {
        if (!allowPublic) return FilingReason.OPTED_OUT;
        if (draft == null) return FilingReason.NO_DRAFT;
        if (draft.disposition() == Draft.Disposition.SECURITY) return FilingReason.SECURITY;
        if (latched || draft.aboutNamedPerson()) return FilingReason.REDACTION;
        if (draft.abuse()) return FilingReason.ABUSE;
        return null;
    }

    /** What routing decided for one revision of a report, before anything is stored. */
    record Outcome(
            Decision decision,
            @Nullable Draft draft,
            Redaction.Result original,
            Map<Redaction.Removal, Integer> counts) {}

    /**
     * The routing decision as a pure function: redaction of the person's text and of the draft, then the gate. The
     * routing evaluation calls it with the most permissive draft to prove the floor that code holds.
     */
    static Outcome evaluate(
            boolean allowPublic,
            ProductFeedback.Kind kind,
            boolean publicRepository,
            boolean privateRepository,
            String message,
            Redaction.Identities known,
            String links,
            @Nullable Draft draft,
            boolean latched) {
        Map<Redaction.Removal, Integer> counts = new EnumMap<>(Redaction.Removal.class);
        var original = Redaction.redact(message, known, links);
        original.counts().forEach((finding, n) -> counts.merge(finding, n, Integer::sum));
        boolean blocking = original.blocking();
        Draft redacted = null;
        if (draft != null) {
            var draftCounts = new EnumMap<Redaction.Removal, Integer>(Redaction.Removal.class);
            redacted = draft.map(text -> {
                var result = Redaction.redact(text, known, links);
                result.counts().forEach((finding, n) -> draftCounts.merge(finding, n, Integer::sum));
                return result.text();
            });
            if (draftCounts.keySet().stream().anyMatch(Redaction.Removal::blocking)) blocking = true;
        }
        var decision =
                decide(new Inputs(allowPublic, kind, publicRepository, privateRepository, redacted, blocking, latched));
        return new Outcome(decision, redacted, original, counts);
    }
}
