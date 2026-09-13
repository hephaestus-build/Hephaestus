/**
 * Agent job handlers — two responsibilities are co-located here:
 *
 * <ul>
 *   <li><b>Job-type dispatch</b> ({@code handler.spi}: {@code JobTypeHandler}, {@code JobSubmission}) plus the
 *       {@code *ReviewHandler}s and {@code JobTypeHandlerRegistry}/{@code JobTypeHandlerConfiguration} that wire
 *       a job type to the code that runs it.</li>
 *   <li><b>The delivery layer</b> — renders immutable observations into SCM feedback and posts it:
 *       {@code DeliveryComposer}, {@code FeedbackDeliveryService}, {@code PracticeDetectionDeliveryService},
 *       {@code DiffNotePoster}, {@code PullRequestCommentPoster}, plus the
 *       {@code FeedbackLedgerRecorder} (the sole write-orchestrator of the {@code practices.feedback} ledger —
 *       see {@code FeedbackLedgerOwnershipTest}) and {@code FeedbackResponseSuppressionFilter}. This is the layer the
 *       detection-context firewall ({@code DetectionReactionFirewallTest}) deliberately EXCLUDES:
 *       reaction-aware delivery is intended here, while reaction-blind detection lives in
 *       {@code agent.context.providers}.</li>
 * </ul>
 *
 * <p>Both responsibilities depend on {@code agent.job} as well as the {@code practices} and
 * {@code integration.scm} named interfaces, so they belong in {@code agent}: relocating delivery into
 * {@code integration} would invert into a Modulith cycle.
 */
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.agent.handler;
