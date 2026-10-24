import type { ItemEvent, ReviewDetail, ReviewRef } from "@/api/types.gen";
import type { StatusDef } from "@/components/common/status-def";
import {
	REVIEW_STATUS_DEFS,
	REVIEW_STOP_REASON_SENTENCES,
	type ReviewStatus,
} from "@/components/practice-vocabulary/review-status-defs";

import {
	countOf,
	type LineageFeedback,
	type LineageRecords,
	type LineageResult,
	type NumberedResult,
	numberedResults,
} from "./lineage-chain";
import { chainReview, ownResults, type TraceNode } from "./review-trace";

export interface ReviewStepState {
	def: StatusDef;
	title: string;
	note?: string;
	assessedNothing: string;
}

const ASSESSED_NOTHING: Record<ReviewStatus, string> = {
	QUEUED: "The review has not started, so it has recorded nothing yet.",
	RUNNING: "The review finishes its practice checks before recording observations here.",
	COMPLETE: "The review ran to the end and recorded no observation.",
	PARTIAL: "The review closed before it recorded an observation.",
	FAILED: "The review stopped before it recorded an observation.",
	CANCELLED: "The review was cancelled before it recorded an observation.",
};

export function reviewStepState(
	review: ReviewRef,
	detail?: Pick<ReviewDetail, "stopReason" | "practices">,
): ReviewStepState {
	const def = REVIEW_STATUS_DEFS[review.status];
	const stopReason = detail?.stopReason ?? null;
	const withheld =
		detail?.practices.filter((practice) => practice.status === "WITHHELD").length ?? 0;
	return {
		def,
		title: `Review ${def.label.toLowerCase()}`,
		note: stopReason === null ? undefined : REVIEW_STOP_REASON_SENTENCES[stopReason],
		// Withheld practices have reasons, not observations; an empty observation list must preserve that distinction.
		assessedNothing:
			withheld > 0
				? `${countOf(withheld, "practice was", "practices were")} withheld with a recorded reason, so no observation stands for ${withheld === 1 ? "it" : "them"}.`
				: ASSESSED_NOTHING[review.status],
	};
}

/** The four stages the rail reads in, so no step is anonymous. */
export type TraceStage = "start" | "review" | "feedback" | "effect";

export const TRACE_STAGE_LABELS: Record<TraceStage, string> = {
	start: "Started by",
	review: "Review",
	feedback: "Feedback",
	effect: "Effect",
};

export type RailItem =
	| {
			kind: "start";
			key: "start";
			at: Date;
			origin: ReviewRef["origin"];
			event: ItemEvent | undefined;
	  }
	| { kind: "review"; key: "review"; at: Date | undefined }
	| { kind: "results"; key: "results"; at: Date | undefined }
	| { kind: "feedback"; key: string; at: Date | undefined; feedback: LineageFeedback }
	| {
			kind: "siblings";
			key: "siblings";
			at: undefined;
			results: number;
			feedback: number;
	  }
	| { kind: "end"; key: "end"; at: undefined };

export interface RailStage {
	stage: TraceStage;
	items: RailItem[];
}

/** The siblings the simple story folds into one line. */
export interface StorySiblings {
	results: number;
	feedback: number;
}

export interface StoryRailOptions {
	startedBy?: ItemEvent | null;
	siblings?: StorySiblings;
}

export function storyRail(
	records: LineageRecords,
	feedback: readonly LineageFeedback[],
	{ startedBy, siblings }: StoryRailOptions = {},
): RailStage[] {
	const review = chainReview(records);
	const start: RailItem[] =
		review === undefined
			? []
			: [
					{
						kind: "start",
						key: "start",
						at: startedBy?.occurredAt ?? review.createdAt,
						origin: review.origin,
						event: startedBy ?? undefined,
					},
				];
	const reviewStage: RailItem[] = [
		// No review to stand on: its step says the record is gone.
		{ kind: "review", key: "review", at: review?.createdAt },
		{
			kind: "results",
			key: "results",
			at: earliest(ownResults(records).map((entry) => entry.recordedAt)),
		},
	];
	const feedbackStage: RailItem[] = feedback.map((entry) => ({
		kind: "feedback",
		key: `feedback:${entry.id}`,
		at: entry.deliveredAt ?? undefined,
		feedback: entry,
	}));
	if (siblings !== undefined && siblings.results + siblings.feedback > 0) {
		feedbackStage.push({ kind: "siblings", key: "siblings", at: undefined, ...siblings });
	}
	return [
		{ stage: "start", items: start },
		{ stage: "review", items: reviewStage },
		{ stage: "feedback", items: feedbackStage },
		{ stage: "effect", items: [{ kind: "end", key: "end", at: undefined }] },
	];
}

function earliest(times: readonly Date[]): Date | undefined {
	let first: Date | undefined;
	for (const at of times) {
		if (first === undefined || at.getTime() < first.getTime()) {
			first = at;
		}
	}
	return first;
}

export function answeredEarlier(
	result: Pick<LineageResult, "reviewId">,
	review: ReviewRef | undefined,
): boolean {
	return review !== undefined && result.reviewId !== review.id;
}

export interface StoryFocus {
	shown: LineageFeedback[];
	/** The rest of the review's pieces, behind "N other pieces of feedback". */
	siblings: LineageFeedback[];
}

export function storyFeedback(
	records: Pick<LineageRecords, "feedback">,
	here: TraceNode | undefined,
	expanded: boolean,
): StoryFocus {
	const byLetter = [...records.feedback].sort(letterOrder);
	if (expanded || (here?.kind !== "result" && here?.kind !== "feedback")) {
		return { shown: byLetter, siblings: [] };
	}
	const told = (entry: LineageFeedback) =>
		here.kind === "result" ? entry.basis.includes(here.id) : entry.id === here.id;
	return {
		shown: byLetter.filter(told),
		siblings: byLetter.filter((entry) => !told(entry)),
	};
}

// Filters hide results without renumbering them.
export function storyResults(
	records: Pick<LineageRecords, "results">,
	here: TraceNode | undefined,
	full: Pick<LineageRecords, "results"> = records,
) {
	const shown = new Set(records.results.map((entry) => entry.id));
	const numbered = numberedResults(full.results).filter((entry) => shown.has(entry.result.id));
	const self =
		here?.kind === "result" ? numbered.find((entry) => entry.result.id === here.id) : undefined;
	return { numbered, self };
}

// Stable ids determine letters because the graph records no composition timestamp.
function letterOrder(a: LineageFeedback, b: LineageFeedback): number {
	return a.id.localeCompare(b.id, "en");
}

export function feedbackLetters(feedback: readonly LineageFeedback[]): Map<string, string> {
	const byLetter = [...feedback].sort(letterOrder);
	return new Map(byLetter.map((entry, index) => [entry.id, letterAt(index)]));
}

function letterAt(index: number): string {
	const letter = String.fromCodePoint(65 + (index % 26));
	return index < 26 ? letter : `${letterAt(Math.floor(index / 26) - 1)}${letter}`;
}

export function sourcesOf(
	feedback: Pick<LineageFeedback, "basis">,
	numbered: readonly NumberedResult[],
): NumberedResult[] {
	return numbered
		.filter((entry) => feedback.basis.includes(entry.result.id))
		.sort((a, b) => a.number - b.number);
}
