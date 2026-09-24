import { cn } from "cn";
import type {
	ReviewFeedbackCounts,
	ReviewFeedbackDisposition,
	ReviewObservation,
	ReviewObservationCounts,
} from "@/api/types.gen";
import type { StatusDef } from "@/components/common/status-def";
import { StatusBadge } from "@/components/common/StatusBadge";
import { ASSESSMENT_STATUS_DEFS } from "@/components/practice-vocabulary/assessment-status-defs";
import { DELIVERY_STATE_DEFS } from "@/components/practice-vocabulary/delivery-outcome-defs";
import {
	type ObservationResultFacts,
	observationResult,
} from "@/components/practice-vocabulary/observation-result";
import { derivedOutcome } from "@/components/practice-vocabulary/outcome-defs";
import { SEVERITY_DEFS } from "@/components/practice-vocabulary/severity-defs";
import { Badge } from "@/components/ui/badge";
import { hasText } from "@/lib/text";

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
 * A backfilled observation and one somebody asked for by hand are both self-selected rather than a
 * random draw from the work, so reading either as though it were live is the mistake this prevents.
 * LIVE renders nothing: badging the ordinary case buries the exceptions.
 */
export function ObservationOriginBadge({ origin }: { origin: ReviewObservation["origin"] }) {
	if (origin === "LIVE") {
		return null;
	}
	return (
		<Badge variant="outline">
			{origin === "BACKFILL" ? "From a review of past work" : "Requested by hand"}
		</Badge>
	);
}

type FeedbackCounts = ReviewFeedbackCounts | ReviewFeedbackDisposition;

export interface ReviewCountSlot {
	key: string;
	/** The registry's own word, lower-cased: a tally that renames what its badge calls the same value
	 * is how one enum ends up with two names. */
	label: string;
	count: number;
}

/**
 * Every outcome is listed whether or not any occurred, and stays a word rather than a badge: badging
 * a count on every row colours the norm, which is what makes the exceptional row invisible.
 */
export function feedbackCountSlots(counts: FeedbackCounts): ReviewCountSlot[] {
	return (
		[
			["DELIVERED", counts.delivered],
			["SUPERSEDED", counts.superseded],
			["PREPARED", counts.prepared],
			["SUPPRESSED", counts.suppressed],
			["FAILED", counts.failed],
		] as const
	).map(([state, count]) => ({
		key: state,
		label: DELIVERY_STATE_DEFS[state].label.toLowerCase(),
		count,
	}));
}

/**
 * Every noun is the registry's own word for the value it counts, so an operator who filtered by a
 * badge's wording is not handed a summary in vocabulary they have never seen. The two assessment
 * counts are nominalised (`Strength` → strengths, `Needs improvement` → improvements) because a
 * count needs a noun, but they keep the stem.
 */
export function observationCountSlots(counts: ReviewObservationCounts): ReviewCountSlot[] {
	return [
		{
			key: "strengths",
			label: counts.strengths === 1 ? "strength" : "strengths",
			count: counts.strengths,
		},
		{
			key: "problems",
			label: counts.problems === 1 ? "improvement" : "improvements",
			count: counts.problems,
		},
		{
			key: "notApplicable",
			label: ASSESSMENT_STATUS_DEFS.NOT_APPLICABLE.label.toLowerCase(),
			count: counts.notApplicable,
		},
		{
			key: "undetermined",
			label: ASSESSMENT_STATUS_DEFS.UNDETERMINED.label.toLowerCase(),
			count: counts.undetermined,
		},
	];
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
export function ReviewCountStrip({ slots, label }: { slots: ReviewCountSlot[]; label: string }) {
	return (
		<ul aria-label={label} className="grid grid-cols-2 gap-x-4 gap-y-0.5 sm:grid-cols-5">
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
	counts: FeedbackCounts;
	/** Names what the numbers count. Only worth passing where the surrounding heading does not
	 * already say it — under one that does, it is noise. */
	prefix?: string;
}) {
	const parts = feedbackCountSlots(counts)
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
