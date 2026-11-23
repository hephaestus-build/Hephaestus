/**
 * The scenario harness: a deterministic run of people, places and provider events against a subject, with a scripted
 * model and exact canary detectors.
 *
 * <p>A {@link de.tum.cit.aet.hephaestus.scenario.Scenario} is a timeline of {@link
 * de.tum.cit.aet.hephaestus.scenario.Step steps}. The {@link de.tum.cit.aet.hephaestus.scenario.Harness} plays it on a
 * controlled clock. A {@link de.tum.cit.aet.hephaestus.scenario.Judge} keeps its own view of who may read what, and
 * checks every model request, entry and sent effect against that view. {@code
 * docs/contributor/add-a-harness-scenario.mdx} explains how to add a scenario.
 */
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.scenario;
