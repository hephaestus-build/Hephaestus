/**
 * The composition stage: the second, separate act that turns measurements into something worth saying.
 *
 * <p>A review measures. It records what is in a piece of work, with the quote that proves it, and those
 * rows are kept forever and never edited. Composition is the other act — deciding whether to say
 * anything at all, to whom, on which surface, and in what words. The two are separated because the good
 * version of each ruins the other: advice written at the moment of measurement can only ever be about the
 * one thing just measured, so it cannot know this is the third time, cannot know the developer was
 * already told, and cannot decide to stay quiet.
 *
 * <p>What lives here is the <em>contract</em> between the composing model and the server: the bounds the
 * stage is given ({@link de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionInputs}),
 * the shape of what it may emit — the review on the work
 * ({@link de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedReview}) and the units for the private
 * lanes ({@link de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedbackUnit}) — and the reader that
 * turns its output into something the lane producers can route
 * ({@link de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionResultParser}).
 *
 * <p><b>The model proposes; Java admits.</b> Nothing here decides that a person will see anything. Each
 * lane's own router still runs afterwards, unchanged in role — giving the model more context is a reason
 * the gate has more to refuse, not a reason to relax it.
 */
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.agent.handler.composition;
