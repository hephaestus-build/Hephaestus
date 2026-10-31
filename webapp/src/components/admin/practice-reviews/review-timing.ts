import type { ReviewTimeShares, ReviewTiming, ReviewTimingStage } from "@/api/types.gen";

import { TRACE_SPAN_LABELS, type TraceSpanKind } from "./trace-bottleneck";

/**
 * A stage of a whole review on the overview, in the order it ran, named as a review's trace and its
 * sheet name the same stretch: until it was asked for, in the queue, preparing, executing, until it
 * reached the developer. Run is the whole run of the reviews that did not record when their
 * execution started, such as older ones, which cannot be split.
 */
export type ReviewStage = Extract<
	TraceSpanKind,
	"asked" | "queue" | "preparation" | "execution" | "run" | "delivery"
>;

export interface ReviewStageDef {
	key: ReviewStage;
	/** The stage's word, as the trace says it: "Until asked", "Queue", "Preparation", "Execution". */
	label: string;
	/** What the stage runs from and to, one short line for the card's (i). */
	text: string;
	/** The parts of the server's timing the stage adds up. */
	parts: readonly (keyof ReviewTimeShares)[];
	/** Shown only when it took some of the time: a stage that only some ranges have. */
	optional?: boolean;
}

/** The stages a typical review adds up. */
export const REVIEW_STAGES: readonly ReviewStageDef[] = [
	{
		key: "asked",
		label: TRACE_SPAN_LABELS.asked,
		text: "from the moment on the work that started the review to its request.",
		parts: ["asked"],
	},
	{
		key: "queue",
		label: TRACE_SPAN_LABELS.queue,
		text: "from the request until the first attempt starts.",
		parts: ["queue"],
	},
	{
		key: "preparation",
		label: TRACE_SPAN_LABELS.preparation,
		text: "from the pick-up until the execution starts: gathering the evidence.",
		parts: ["preparation"],
	},
	{
		key: "execution",
		label: TRACE_SPAN_LABELS.execution,
		text: "from the start of the execution until the review ends.",
		parts: ["model"],
	},
	{
		key: "run",
		label: `${TRACE_SPAN_LABELS.run} (not split)`,
		text: "the whole run of older reviews that did not record when their execution started.",
		parts: ["runUnsplit"],
		optional: true,
	},
	{
		key: "delivery",
		label: TRACE_SPAN_LABELS.delivery,
		text: "from the end of the review until its first comment on the work.",
		parts: ["posted"],
	},
];

/** A stage's timing once it measured at least one review. */
export type MeasuredTiming = ReviewTimingStage & { medianSeconds: number; p90Seconds: number };

/** The timing when it measured at least one review; otherwise there is nothing to say. */
export function measured(stage: ReviewTimingStage): MeasuredTiming | undefined {
	return stage.count > 0 && stage.medianSeconds !== null && stage.p90Seconds !== null
		? { ...stage, medianSeconds: stage.medianSeconds, p90Seconds: stage.p90Seconds }
		: undefined;
}

/** One stage of the typical review, with its part of it. */
export interface MeasuredStage {
	def: ReviewStageDef;
	/** The stage's part of the reviews' time, from 0 to 1. */
	share: number;
	/** That part of the typical review, in seconds. */
	seconds: number;
}

/**
 * A typical review, measured over one set of whole reviews: the median of their totals, from the
 * moment that started each, or its request, and each stage's part of their time, so the parts add
 * up to the median. To a comment when reviews posted one on the work by themselves; otherwise to the
 * end of the review, so the card never says "to comment" where nothing commented.
 */
export interface TypicalReview {
	until: "comment" | "end";
	timing: MeasuredTiming;
	stages: MeasuredStage[];
}

export function typicalReview(timing: ReviewTiming): TypicalReview | undefined {
	const toComment = measured(timing.total);
	if (toComment !== undefined) {
		return {
			until: "comment",
			timing: toComment,
			stages: stagesOf(timing.totalShares, toComment, "comment"),
		};
	}
	const toEnd = measured(timing.finished);
	return toEnd === undefined
		? undefined
		: { until: "end", timing: toEnd, stages: stagesOf(timing.finishedShares, toEnd, "end") };
}

function stagesOf(
	shares: ReviewTimeShares | null,
	timing: MeasuredTiming,
	until: TypicalReview["until"],
): MeasuredStage[] {
	if (shares === null) {
		return [];
	}
	return REVIEW_STAGES.flatMap((def) => {
		const parts = def.parts.map((part) => shares[part]);
		if (parts.some((part) => part === null)) {
			return [];
		}
		const share = parts.reduce<number>((sum, part) => sum + (part ?? 0), 0);
		if (def.optional === true && share <= 0) {
			return [];
		}
		let measuredDef = def;
		if (until === "comment" && def.key === "execution") {
			measuredDef = {
				...def,
				text: "from the start of the execution until the review on the work is accepted (the review’s end for older reviews).",
			};
		} else if (until === "comment" && def.key === "delivery") {
			measuredDef = {
				...def,
				text: "from acceptance of the review on the work until its first comment (from the review’s end for older reviews).",
			};
		}
		return [{ def: measuredDef, share, seconds: share * timing.medianSeconds }];
	});
}
