package de.tum.cit.aet.hephaestus.productfeedback.drafting;

import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionEnded;
import java.util.UUID;

/**
 * What a draft action reports to the filing lifecycle. A {@code draft_report} action drafts one generation of a report;
 * product feedback keeps the lifecycle, the prompt and every trust decision.
 */
public interface ProductFeedbackDrafts {

    /** What a {@code draft_report} action carries: the report and the generation it drafts. */
    record Payload(UUID feedbackId, int generation) {}

    /** Reports a claimed attempt or a capacity deferral through the filing lifecycle, while its action is held. */
    boolean claimed(ActionAttempt attempt, UUID feedbackId, int generation);

    boolean deferred(ActionAttempt attempt, UUID feedbackId, int generation);

    /**
     * Keeps the model's answer on the report for the action's end, if the attempt still holds its action.
     *
     * @return false when the attempt lost its action; nothing was stored
     */
    boolean answered(ActionAttempt attempt, UUID feedbackId, int generation, String answer);

    /** Moves the report on when its {@code draft_report} action ended, in the transaction that ended it. */
    void ended(ActionEnded ended);
}
