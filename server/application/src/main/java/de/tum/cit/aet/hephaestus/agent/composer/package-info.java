/**
 * Composes the feedback of an admitted item review: one bounded model session per destination group, checked, and
 * prepared through the fenced preparation. {@code CompositionSessions} is the server side: it renders each session
 * from the retained basis and runs every tool. The composer loop that calls the model runs in the review's sandbox as a
 * {@code review_compose} run ({@code review-composer.ts}), through {@link ReviewComposeSandboxKind}. The
 * golden fixtures under {@code src/test/resources/agent/golden-prompts/composition-turns} hold the turns and tool
 * schemas it renders.
 */
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.agent.composer;
