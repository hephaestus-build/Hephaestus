package de.tum.cit.aet.hephaestus.config;

/**
 * Name of the executor the two feedback-preparation lanes and approved-feedback delivery run on.
 *
 * <p>A constant rather than a literal on each {@code @Async} so the listeners and the bean definition
 * cannot drift apart: a typo in the qualifier is not an error, it silently falls back to the default
 * executor and quietly undoes the isolation this pool exists to provide.
 *
 * <p>Isolation, not durability. This pool can still reject: it is bounded, and it must be, because an
 * unbounded queue in front of database work trades a visible rejection for an invisible backlog. What
 * makes a rejection survivable is {@code FeedbackLanePreparationSweeper} and {@code
 * ApprovedFeedbackRecovery}, not this pool. The pool's job is narrower — stop a provider sync's twenty-odd
 * activity listeners from being the reason a developer's feedback is late.
 */
public final class FeedbackLaneExecutor {

    public static final String BEAN_NAME = "feedbackLaneExecutor";

    private FeedbackLaneExecutor() {}
}
