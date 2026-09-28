import { cn } from "cn";
import type { ReviewFeedbackCounts, ReviewObservation } from "@/api/types.gen";
import type { StatusDef } from "@/components/common/status-def";
import { StatusBadge } from "@/components/common/StatusBadge";
import { OBSERVATION_ORIGIN_DEFS } from "@/components/practice-vocabulary/observation-origin-defs";
import {
	type ObservationResultFacts,
	observationResult,
} from "@/components/practice-vocabulary/observation-result";
import { derivedOutcome } from "@/components/practice-vocabulary/outcome-defs";
import { SEVERITY_DEFS } from "@/components/practice-vocabulary/severity-defs";
import { hasText } from "@/lib/text";

import { feedbackSlots, type OutcomeSlot } from "./review-outcomes";

export function ObservationResultBadge({
	observation,
}: {
	observation: ObservationResultFacts & Pick<ReviewObservation, "severity">;
}) {
	const severity = observationSeverity(observation);
	return (
		<span className="flex flex-wrap items-center gap-1.5">
			<StatusBadge def={observationResult(observation)} />
			{severity && <StatusBadge def={severity} />}
		</span>
	);
}

/**
 * Severity is only ever read alongside a shortfall — a "Minor" beside a strength would be a cost for
 * something that cost nothing.
 */
export function observationSeverity(
	observation: ObservationResultFacts & Pick<ReviewObservation, "severity">,
): StatusDef | undefined {
	return observation.assessmentStatus === "ASSESSED" &&
		observation.presence &&
		observation.assessment &&
		derivedOutcome(observation.presence, observation.assessment) === "NEGATIVE" &&
		observation.severity
		? SEVERITY_DEFS[observation.severity]
		: undefined;
}

/**
 * LIVE renders nothing: badging the ordinary case buries the exceptions. The words are the
 * registry's, so the operator's console and the developer's own surface name an origin alike.
 */
export function ObservationOriginBadge({ origin }: { origin: ReviewObservation["origin"] }) {
	if (origin === "LIVE") {
		return null;
	}
	return <StatusBadge def={OBSERVATION_ORIGIN_DEFS[origin]} />;
}

/**
 * Every slot is drawn, zeroes included, and the template is fixed at each width, so the strip's
 * width does not depend on the numbers in it: a tally that dropped its zeroes would put the same
 * count at a different x on every row, reflow under the reader as a poll refreshes, and make an
 * absent count indistinguishable from one this screen does not render at all.
 *
 * Each number keeps its word beside it, so a screen reader gets "0 improvements" rather than a bare
 * nought.
 */
export function ReviewCountStrip({ slots, label }: { slots: OutcomeSlot[]; label: string }) {
	return (
		<ul aria-label={label} className="grid grid-cols-2 gap-x-4 gap-y-0.5 sm:grid-cols-4">
			{slots.map((slot) => (
				<li key={slot.key} className="flex min-w-0 items-baseline gap-1">
					<span
						className={cn(
							"tabular-nums",
							slot.count > 0 ? "font-medium text-foreground" : "text-muted-foreground/60",
						)}
					>
						{slot.count}
					</span>
					{
						// A real space, so the pair reads "0 improvements" to a screen reader and in a test.
						// Flex drops whitespace-only children, so the visible gap is still the one `gap-1`
						// sets and this adds nothing to the layout.
						" "
					}
					<span className="min-w-0 break-words">{slot.label}</span>
				</li>
			))}
		</ul>
	);
}

export function FeedbackCountsSummary({
	counts,
	prefix,
}: {
	counts: ReviewFeedbackCounts;
	/** Names what the numbers count. Only worth passing where the surrounding heading does not
	 * already say it — under one that does, it is noise. */
	prefix?: string;
}) {
	const parts = feedbackSlots(counts)
		.filter((slot) => slot.count > 0)
		.map((slot) => `${slot.count} ${slot.label}`);
	if (parts.length === 0) {
		return <span>No feedback composed</span>;
	}
	return (
		<span>
			{hasText(prefix) && `${prefix} `}
			{parts.join(" · ")}
		</span>
	);
}
