/**
 * Opportunity-indexed trend estimation: whether a developer's evidence for a practice, or for a group, is
 * moving.
 *
 * <p><b>The one idea the package rests on:</b> the unit of analysis is an evidence opportunity, meaning one
 * reviewed work item, and never a unit of clock time. Repository activity is bursty, so a calendar bin gives
 * unequal and partly empty samples; a day with six pull requests and a day with one are not two comparable
 * draws. Timestamps survive as provenance only, never as a bin, an axis, or a comparison boundary.
 *
 * <p>Same choice as opportunity-indexed developer-facing models (Bayesian Knowledge Tracing, Performance Factors
 * Analysis), which count practice opportunities rather than calendar periods.
 *
 * <h2>The pipeline, in order</h2>
 * <ol>
 *   <li>{@code OutcomeVector} — what one observation, or one run's worth of them, said: strengths and safe
 *       avoidances against commission problems and omission gaps, plus the ones that produced no verdict.</li>
 *   <li>{@code EvidenceOpportunity} — one reviewed work item, its outcomes normalized across the latest run
 *       that judged it. This is the sample.</li>
 *   <li>{@code OpportunityBundler} — groups observations per piece of reviewed work and splits the newest opportunities
 *       into the two bundles a comparison needs.</li>
 *   <li>{@code BetaPosterior} — the mathematics, and the only place any is done. A Jeffreys-prior beta
 *       posterior per bundle, and their difference on a discrete grid because a difference of two beta
 *       variables has no closed form.</li>
 *   <li>{@code TrendDirectionRule} — the policy: a region of practical equivalence around zero, and a
 *       credibility threshold the mass must clear. Separated from the mathematics on purpose, so
 *       "how sure are we" and "what do we call that" can be argued about one at a time.</li>
 *   <li>{@code PracticeTrend} — the result, and {@code TrendSupport} the provenance beside it: how much
 *       evidence, over which span, still missing how much.</li>
 *   <li>{@code PracticeTrendService} — the only producer of trend results in the application. Group results
 *       are an inverse-variance weighted combination of practice results, so a thinly evidenced practice
 *       speaks more quietly than a well evidenced one.</li>
 * </ol>
 *
 * <h2>The three small enums, and why they are separate files</h2>
 * They are vocabulary, not logic, and Java admits one public top-level type per file. The house rule is to
 * nest an enum in the type that owns it — as {@code PracticeStandingDTO.Standing} is nested — and to keep
 * it top-level when several types share it. These are shared:
 * <ul>
 *   <li>{@code TrendDirection} — the package's OUTWARD term. It reaches {@code practices.dto} and
 *       {@code practices.observation.dto}; nesting it in any one of its seven users would invent an
 *       ownership that does not exist.</li>
 *   <li>{@code TrendScope}, {@code TrendBundle} — internal to this subtree. Each is used only here and in
 *       {@code trend.dto}, which is what makes them safe to change without looking further afield.</li>
 * </ul>
 *
 * <p>{@code TrendProperties} carries the research parameters — bundle size, the practical-equivalence half
 * width, the credibility threshold — and validates their relationships at startup, because a
 * minimum bundle larger than the bundle itself would silently report "insufficient evidence" forever.
 *
 * <h2>What this package deliberately does not say</h2>
 * There is no verdict for "nothing is changing" and no graded support level. Both were measured against the
 * evidence this surface actually has and removed. A stability claim needs the posterior difference to sit
 * inside the equivalence band with 90% credibility, which takes roughly 28 opportunities for a practice near
 * the middle and over a hundred at the current band width — against the four per bundle available here.
 * A support level had one reachable value once both bundles must be full, and across every combination
 * reachable at that size it never changed a verdict. {@code UNCERTAIN} carries the first case honestly and
 * {@code TrendSupport}'s counts carry the second.
 */
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.practices.observation.trend;
