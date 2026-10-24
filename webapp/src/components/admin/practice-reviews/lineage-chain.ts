import type {
	ItemRef,
	ProblemSummary,
	ReviewBoundFeedback,
	ReviewBoundResult,
	ReviewLineage,
	ReviewLineageNode,
	ReviewRef,
} from "@/api/types.gen";
import type { BadgeVariant, StatusDef } from "@/components/common/status-def";
import { deliveryOutcome } from "@/components/practice-vocabulary/delivery-state-defs";
import {
	FEEDBACK_LOCATION_DEFS,
	type FeedbackLocation,
} from "@/components/practice-vocabulary/feedback-location-defs";
import { FEEDBACK_WITHDRAWN_DEF } from "@/components/practice-vocabulary/feedback-withdrawal-defs";
import { OUTCOME_DEFS, type Outcome } from "@/components/practice-vocabulary/outcome-defs";
import { durationBetween } from "@/lib/dates";

import { QUEUE_WAIT_THRESHOLDS } from "./review-attention-thresholds";

/**
 * A gap this long between two steps is a delay worth seeing, so it is coloured: the overview's amber
 * mark for the queue, so a trace and the overview call the same wait slow.
 */
export const SLOW_GAP_SECONDS = QUEUE_WAIT_THRESHOLDS.warningSeconds;

/**
 * Whether a gap is a long wait, judged on the minutes `gapLabel` shows, so a gap shown as "30 min"
 * is always coloured as one.
 */
export function isSlowGap(seconds: number): boolean {
	return seconds >= 60 && Math.round(seconds / 60) * 60 >= SLOW_GAP_SECONDS;
}

/** A dot in the tone of a status, where an icon would be too large. */
const TONE_BG: Record<BadgeVariant, string> = {
	default: "bg-primary",
	secondary: "bg-muted-foreground/60",
	destructive: "bg-destructive",
	outline: "bg-muted-foreground/60",
	success: "bg-success",
	warning: "bg-warning",
};

export function toneBg(variant: BadgeVariant): string {
	return TONE_BG[variant];
}

const TONE_BORDER: Record<BadgeVariant, string> = {
	default: "border-primary",
	secondary: "border-border",
	destructive: "border-destructive",
	outline: "border-border",
	success: "border-success",
	warning: "border-warning",
};

export function toneBorder(variant: BadgeVariant): string {
	return TONE_BORDER[variant];
}

/** A tinted chip in the tone of a status: readable in both themes, where a filled dot would not be. */
const TONE_CHIP: Record<BadgeVariant, string> = {
	default: "border-primary/50 bg-primary/10 text-primary",
	secondary: "border-muted-foreground/40 bg-muted text-muted-foreground",
	destructive: "border-destructive/50 bg-destructive/10 text-destructive",
	outline: "border-muted-foreground/40 bg-muted text-muted-foreground",
	success: "border-success/50 bg-success/10 text-success",
	warning: "border-warning/50 bg-warning/10 text-warning",
};

export function toneChip(variant: BadgeVariant): string {
	return TONE_CHIP[variant];
}

export interface LineageResult extends ReviewBoundResult {
	problems: ProblemSummary[];
}

export interface LineageFeedback extends ReviewBoundFeedback {
	basis: string[];
	problemBasis: string[];
}

export interface LineageRecords {
	root: ReviewLineageNode | undefined;
	item: ItemRef | undefined;
	reviews: ReviewRef[];
	results: LineageResult[];
	feedback: LineageFeedback[];
}

// Edges to absent nodes cannot establish relationships.
export function lineageRecords(lineage: ReviewLineage): LineageRecords {
	const byKey = new Map(lineage.nodes.map((node) => [node.id, node]));
	const targets = (from: string, kind: ReviewLineageNode["kind"]) =>
		lineage.edges
			.filter((edge) => edge.from === from)
			.map((edge) => byKey.get(edge.to))
			.filter((node) => node?.kind === kind);
	const sources = (to: string, kind: ReviewLineageNode["kind"]) =>
		lineage.edges
			.filter((edge) => edge.to === to)
			.map((edge) => byKey.get(edge.from))
			.filter((node) => node?.kind === kind);
	const item = lineage.nodes.find((node) => node.item !== null)?.item ?? undefined;
	const reviews: ReviewRef[] = [];
	const results: LineageResult[] = [];
	const feedback: LineageFeedback[] = [];
	for (const node of lineage.nodes) {
		if (node.review !== null) {
			reviews.push(node.review);
		}
		if (node.result !== null) {
			const problems = targets(node.id, "PROBLEM")
				.map((entry) => entry?.problem ?? null)
				.filter((entry) => entry !== null);
			results.push({ ...node.result, problems });
		}
		if (node.feedback !== null) {
			const basis = sources(node.id, "RESULT")
				.map((entry) => entry?.result?.id)
				.filter((entry) => entry !== undefined);
			const problemBasis = sources(node.id, "PROBLEM")
				.map((entry) => entry?.problem?.id)
				.filter((entry) => entry !== undefined);
			feedback.push({ ...node.feedback, basis, problemBasis });
		}
	}
	return { root: byKey.get(lineage.rootId), item, reviews, results, feedback };
}

export function feedbackOf(
	feedback: readonly LineageFeedback[],
	resultId: string,
): LineageFeedback[] {
	return feedback.filter((entry) => entry.basis.includes(resultId));
}

export function feedbackLocation(
	feedback: Pick<ReviewBoundFeedback, "destination">,
	item: ItemRef | undefined,
): FeedbackLocation {
	switch (feedback.destination) {
		case "ON_WORK": {
			if (item?.provider === "GITHUB") {
				return "GITHUB";
			}
			if (item?.provider === "GITLAB") {
				return "GITLAB";
			}
			return "OTHER_WORK";
		}
		case "CONVERSATION": {
			return "NEXT_CONVERSATION";
		}
		case "PRACTICE_PAGE": {
			return "PRACTICE_PAGES";
		}
	}
}

/** "On GitHub" reads "on GitHub" after a verb; a provider name keeps its case. */
export function lowerFirst(value: string): string {
	return value.charAt(0).toLowerCase() + value.slice(1);
}

export function feedbackStatus(feedback: ReviewBoundFeedback): StatusDef {
	return deliveryOutcome({ ...feedback, stopReason: null });
}

export interface BranchOutcome {
	/** Icon and tone. */
	def: StatusDef;
	text: string;
	detail?: string;
	/** When it happened, where the record says. */
	at?: Date;
	/**
	 * `delivered` reached the developer and stands; `withheld` never reached them, or no longer
	 * stands; `waiting` is still on its way or waits for a person; `replaced` a newer piece took its
	 * place.
	 */
	kind: "delivered" | "withheld" | "waiting" | "replaced" | "other";
}

// An admin withdrawal overrides stored delivery state.
export function branchOutcome(
	feedback: ReviewBoundFeedback,
	item: ItemRef | undefined,
): BranchOutcome {
	const def = feedbackStatus(feedback);
	const location = FEEDBACK_LOCATION_DEFS[feedbackLocation(feedback, item)];
	const at = feedback.deliveredAt ?? undefined;
	if (feedback.withdrawn) {
		return {
			def: FEEDBACK_WITHDRAWN_DEF,
			text: "Withdrawn by an admin",
			detail: reachedDeveloper(feedback)
				? `Delivered ${lowerFirst(location.label)}, then taken off them.`
				: "Taken back before it reached the developer.",
			kind: "withheld",
		};
	}
	switch (feedback.state) {
		case "REJECTED": {
			return {
				def,
				text: "Rejected by an admin",
				detail: "An admin decided it should not be sent.",
				kind: "withheld",
			};
		}
		case "AWAITING_APPROVAL": {
			return { def, text: def.label, kind: "waiting" };
		}
		case "READY": {
			return { def, text: def.label, kind: "waiting" };
		}
		case "DELIVERED": {
			return { def, text: `Delivered ${lowerFirst(location.label)}`, at, kind: "delivered" };
		}
		case "REPLACED": {
			return { def, text: def.label, kind: "replaced" };
		}
		case "WITHHELD": {
			return {
				def,
				text: at === undefined ? def.label : "Delivered, later withheld",
				at,
				kind: "withheld",
			};
		}
		case "FAILED": {
			return { def, text: def.label, kind: "withheld" };
		}
		case "UNCONFIRMED": {
			return { def, text: def.label, kind: "other" };
		}
	}
}

/** Whether the developer could read it at some point, though it may not stand now. */
export function reachedDeveloper(feedback: ReviewBoundFeedback): boolean {
	return feedback.state === "DELIVERED" || feedback.deliveredAt !== null;
}

/** "+40 s", "+2 min", "+1 h 17 min", "+2 d 3 h": {@link durationText} with a sign, read between two steps. */
export function gapLabel(from: Date, to: Date): string {
	return `${to.getTime() < from.getTime() ? "−" : "+"}${durationBetween(from, to)}`;
}

const OUTCOME_ORDER: readonly Outcome[] = [
	"NOT_MET",
	"MET",
	"COULD_NOT_DETERMINE",
	"DOES_NOT_APPLY",
];

export function verdictCounts(
	results: readonly ReviewBoundResult[],
): { outcome: Outcome; count: number }[] {
	return OUTCOME_ORDER.map((outcome) => ({
		outcome,
		count: results.filter((result) => result.outcome === outcome).length,
	})).filter((entry) => entry.count > 0);
}

export function verdictSummary(results: readonly ReviewBoundResult[]): string {
	const parts = verdictCounts(results).map(
		({ outcome, count }) => `${count} ${OUTCOME_DEFS[outcome].label.toLowerCase()}`,
	);
	return `${countOf(results.length, "observation", "observations")}: ${parts.join(", ")}`;
}

export function countOf(count: number, singular: string, plural: string): string {
	return `${count} ${count === 1 ? singular : plural}`;
}

export interface NumberedResult {
	result: LineageResult;
	number: number;
}

export type NumberedById = ReadonlyMap<string, NumberedResult>;

// Number results from their full review so every trace keeps the same identities.
export function numberedResults(results: readonly LineageResult[]): NumberedResult[] {
	const byTime = [...results].sort(
		(a, b) => a.recordedAt.getTime() - b.recordedAt.getTime() || a.id.localeCompare(b.id, "en"),
	);
	const groups = new Map<string, LineageResult[]>();
	for (const result of byTime) {
		const group = groups.get(result.practiceSlug) ?? [];
		group.push(result);
		groups.set(result.practiceSlug, group);
	}
	return [...groups.values()].flat().map((result, index) => ({ result, number: index + 1 }));
}

export function silentNotMet(
	records: Pick<LineageRecords, "results" | "feedback">,
): LineageResult[] {
	return records.results.filter(
		(result) =>
			result.outcome === "NOT_MET" && feedbackOf(records.feedback, result.id).length === 0,
	);
}

export interface StageCounts {
	results: number;
	feedback: number;
	reached: number;
}

export function stageCounts(records: Pick<LineageRecords, "results" | "feedback">): StageCounts {
	return {
		results: records.results.length,
		feedback: records.feedback.length,
		reached: records.feedback.filter(reachedDeveloper).length,
	};
}
