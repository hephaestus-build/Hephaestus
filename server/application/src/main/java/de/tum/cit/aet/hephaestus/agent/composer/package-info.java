/**
 * Composes the feedback of an admitted item review: one bounded model session per destination group, checked, and
 * prepared through the fenced preparation. {@code CompositionSessions} is the server side: it renders each session
 * from the retained basis and runs every tool. {@code WorkerFeedbackComposer} is the loop that calls the model. The
 * golden fixtures under {@code src/test/resources/agent/golden-prompts/composition-turns} hold the turns and tool
 * schemas it renders.
 */
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.agent.composer;
