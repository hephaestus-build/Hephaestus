import {
	CircleAlertIcon,
	CircleCheckIcon,
	CircleDashedIcon,
	CircleSlashIcon,
	ClockIcon,
	EyeOffIcon,
	HourglassIcon,
	type LucideIcon,
	TimerOffIcon,
} from "lucide-react";

import type { ReviewBoundFeedback, ReviewDetail, ReviewLineage, ReviewRef } from "@/api/types.gen";
import type { BadgeVariant } from "@/components/common/status-def";
import { deliveryOutcome } from "@/components/practice-vocabulary/delivery-state-defs";
import {
	PARTIAL_REVIEW_SCOPE,
	REVIEW_JOB_PROCESSING_DEFS,
	REVIEW_STOP_REASON_SENTENCES,
} from "@/components/practice-vocabulary/review-status-defs";
import { durationBetween } from "@/lib/dates";
import { hasText } from "@/lib/text";

import { firstStartedAt, invocationCompletedAt, latestAttempt } from "./job-utils";
import {
	countOf,
	feedbackOf,
	isSlowGap,
	type LineageFeedback,
	type LineageRecords,
	lineageRecords,
	reachedDeveloper,
	silentNotMet,
} from "./lineage-chain";
import type { TraceStage } from "./lineage-story";
import type { TraceNode } from "./review-trace";

export type TraceSpanKind = "asked" | "queue" | "preparation" | "execution" | "run" | "delivery";

export const TRACE_SPAN_LABELS: Record<TraceSpanKind, string> = {
	asked: "Until asked",
	queue: "Queue",
	preparation: "Preparation",
	execution: "Execution",
	run: "Run",
	delivery: "To developer",
};

export type MeasuredSpanKind = Extract<TraceSpanKind, "asked" | "queue" | "run" | "delivery">;

export interface TraceSpan {
	kind: MeasuredSpanKind;
	/** "Queue", "Run": the word on the strip. */
	label: string;
	/** What the span runs from and to, for its tooltip. */
	hint: string;
	from: Date;
	to: Date;
	/** "2 h 10 min". */
	text: string;
	/** Half an hour or more: the wait is coloured. */
	slow: boolean;
}

/** Where on the trace the path was lost or slowed: the stage its line names. */
export type BottleneckStage = TraceStage;

/**
 * The one thing a reader of a trace looks for first: where the path was lost, held or slowed, and
 * why, in one line next to the verdict.
 *
 * - `failed`: the review failed or was cancelled before it finished.
 * - `lost`: a result or feedback did not arrive or no longer stands. A partial review is placed
 *   here, at its results, because it may have run to the end; its recorded reason says why.
 * - `slow`: everything arrived, but one wait took half an hour or more.
 * - `waiting`: the path is still on its way and waits on something named.
 * - `none`: nothing was lost and no wait stands out.
 */
export type BottleneckKind = "failed" | "lost" | "slow" | "waiting" | "none";

export interface TraceBottleneck {
	kind: BottleneckKind;
	stage: BottleneckStage;
	/** "Withheld", "Failed", "Slow queue": the first words, bold. */
	title: string;
	/** One sentence: what happened and why, with the time it cost where the record says. */
	text: string;
	tone: BadgeVariant;
	icon: LucideIcon;
	/** The span that took longest where it was slow, also coloured on the strip. */
	span?: MeasuredSpanKind;
	/** The reason in a few words, for where the verdict above already says the whole sentence. */
	short?: string;
}

function span(
	kind: MeasuredSpanKind,
	hint: string,
	from: Date | null | undefined,
	to: Date | null | undefined,
): TraceSpan | undefined {
	if (from == null || to == null || to.getTime() < from.getTime()) {
		return undefined;
	}
	return {
		kind,
		label: TRACE_SPAN_LABELS[kind],
		hint,
		from,
		to,
		text: durationBetween(from, to),
		slow: isSlowGap((to.getTime() - from.getTime()) / 1000),
	};
}

export function traceReview(
	records: LineageRecords,
	detail: ReviewDetail | undefined,
): ReviewRef | undefined {
	if (detail !== undefined) {
		return detail;
	}
	const rootReviewId = records.root?.review?.id ?? records.root?.result?.reviewId;
	return records.reviews.find((review) => review.id === rootReviewId) ?? records.reviews[0];
}

function reviewFeedback(records: LineageRecords, review: ReviewRef | undefined): LineageFeedback[] {
	if (review === undefined) {
		return records.feedback;
	}
	const own = new Set(
		records.results.filter((result) => result.reviewId === review.id).map((result) => result.id),
	);
	return records.feedback.filter((entry) => entry.basis.some((id) => own.has(id)));
}

function feedbackOnPath(
	records: LineageRecords,
	review: ReviewRef | undefined,
	here: TraceNode | undefined,
): LineageFeedback[] {
	if (here?.kind === "feedback") {
		return records.feedback.filter((entry) => entry.id === here.id);
	}
	if (here?.kind === "result") {
		return feedbackOf(records.feedback, here.id);
	}
	return reviewFeedback(records, review);
}

export function traceSpans(
	lineage: ReviewLineage,
	here: TraceNode | undefined,
	detail?: ReviewDetail,
): TraceSpan[] {
	const records = lineageRecords(lineage);
	const review = traceReview(records, detail);
	if (review === undefined) {
		return [];
	}
	// Once an attempt continued, its first pickup is gone, so the queue and run are not measured.
	const pickedUp = firstStartedAt(detail?.runtime?.jobs ?? []);
	const [delivered] = feedbackOnPath(records, review, here)
		.map((entry) => entry.deliveredAt)
		.filter((deliveredAt) => deliveredAt !== null)
		.sort((a, b) => a.getTime() - b.getTime());
	return [
		span(
			"asked",
			"From the event that started it to when the review was asked for",
			detail?.startedBy?.occurredAt,
			review.createdAt,
		),
		span(
			"queue",
			"From when the review was asked for to when a worker picked it up",
			review.createdAt,
			pickedUp,
		),
		span(
			"run",
			"From the first worker pickup to when all recorded worker invocations ended",
			pickedUp,
			invocationCompletedAt(detail?.runtime?.jobs ?? []),
		),
		span(
			"delivery",
			"From when the review finished to when its feedback first reached the developer",
			review.completedAt,
			delivered,
		),
	].filter((entry) => entry !== undefined);
}

export interface FeedbackLoss {
	title: string;
	text: string;
	short: string;
	tone: LossTone;
}

type LossTone = "destructive" | "warning" | "secondary";

// The graph supplies no stop reason; withheld feedback cannot be given an invented explanation.
export function feedbackLoss(feedback: ReviewBoundFeedback): FeedbackLoss | undefined {
	if (feedback.withdrawn) {
		return {
			title: "Withdrawn",
			text: reachedDeveloper(feedback)
				? "An admin withdrew it after it was delivered."
				: "An admin withdrew it before it reached the developer.",
			short: "withdrawn by an admin",
			tone: "secondary",
		};
	}
	switch (feedback.state) {
		case "REJECTED": {
			return {
				title: "Rejected",
				text: "An admin rejected it before it was sent.",
				short: "rejected",
				tone: "secondary",
			};
		}
		case "FAILED": {
			// Partly delivered: a copy reached the developer, so the piece is not lost; its trace says which part failed.
			if (feedback.deliveredAt !== null) {
				return undefined;
			}
			return {
				title: "Delivery failed",
				text: "Sending it failed, so it never reached the developer.",
				short: "delivery failed",
				tone: "destructive",
			};
		}
		case "REPLACED": {
			// Replaced after it reached the developer is the ordinary course of a re-review.
			if (feedback.deliveredAt !== null) {
				return undefined;
			}
			return {
				title: "Replaced",
				text: "Newer feedback replaced it.",
				short: "newer feedback took its place",
				tone: "secondary",
			};
		}
		case "WITHHELD": {
			return {
				title: "Withheld",
				text:
					feedback.deliveredAt === null
						? "It was withheld before it reached the developer. Its feedback record says why."
						: "It was delivered, then withheld. Its feedback record says why.",
				short: "withheld",
				tone: "warning",
			};
		}
		case "AWAITING_APPROVAL":
		case "READY":
		case "DELIVERED":
		case "UNCONFIRMED": {
			return undefined;
		}
	}
}

function lossesByReason(losses: readonly { short: string }[]): string {
	const counts = new Map<string, number>();
	for (const loss of losses) {
		counts.set(loss.short, (counts.get(loss.short) ?? 0) + 1);
	}
	return [...counts.entries()]
		.sort((a, b) => b[1] - a[1])
		.map(([short, count]) => (losses.length === 1 ? short : `${short} (${count})`))
		.join(", ");
}

function lost(losses: readonly FeedbackLoss[]): { tone: LossTone; icon: LucideIcon } {
	let tone: LossTone = "secondary";
	for (const loss of losses) {
		if (loss.tone === "destructive" || (loss.tone === "warning" && tone === "secondary")) {
			({ tone } = loss);
		}
	}
	return { tone, icon: EyeOffIcon };
}

// Missing feedback for a Not met result warrants attention; it does not prove a delivery failure.
const NOT_TOLD = { tone: "warning", icon: EyeOffIcon } as const;

function runtimeFailure(detail: ReviewDetail | undefined): { error?: string; latest?: number } {
	const jobs = detail?.runtime?.jobs ?? [];
	const failed = [...jobs].reverse().find((job) => hasText(job.errorMessage));
	return {
		error: hasText(failed?.errorMessage)
			? failed.errorMessage.trim().replace(/\.?$/u, ".")
			: undefined,
		latest: latestAttempt(jobs),
	};
}

function reviewBottleneck(
	review: ReviewRef,
	detail: ReviewDetail | undefined,
	now: Date,
): TraceBottleneck | undefined {
	const stopReason = detail?.stopReason ?? null;
	const because = stopReason === null ? undefined : REVIEW_STOP_REASON_SENTENCES[stopReason];
	const { error, latest } = runtimeFailure(detail);
	const tries = latest === undefined ? "" : ` It stopped on attempt ${latest}.`;
	switch (review.status) {
		case "FAILED": {
			const timedOut = stopReason === "TIMED_OUT" || stopReason === "DEADLINE";
			return {
				kind: "failed",
				stage: "review",
				title: timedOut ? "Review timed out" : "Review failed",
				text: `${error ?? because ?? "It stopped on an error. The error is not recorded here."}${tries}`,
				tone: "destructive",
				icon: timedOut ? TimerOffIcon : CircleAlertIcon,
			};
		}
		case "PARTIAL": {
			// A partial review may have stopped early or run to the end with a practice failed or
			// withheld, so only the recorded reason says which; the line never assumes an early stop.
			return {
				kind: "lost",
				stage: "review",
				title: "Partial review",
				text: [because, PARTIAL_REVIEW_SCOPE[review.kind]].filter(hasText).join(" "),
				tone: "warning",
				icon: CircleDashedIcon,
			};
		}
		case "CANCELLED": {
			return {
				kind: "failed",
				stage: "review",
				title: "Review cancelled",
				text: because ?? "Somebody stopped it before it finished.",
				tone: "outline",
				icon: CircleSlashIcon,
			};
		}
		case "QUEUED": {
			const slow = isSlowGap((now.getTime() - review.createdAt.getTime()) / 1000);
			return {
				kind: "waiting",
				stage: "review",
				title: "In the queue",
				text: `It has waited ${durationBetween(review.createdAt, now)} for a worker so far.`,
				tone: slow ? "warning" : "secondary",
				icon: ClockIcon,
				span: "queue",
			};
		}
		case "RUNNING": {
			const jobs = detail?.runtime?.jobs ?? [];
			if (invocationCompletedAt(jobs) !== undefined) {
				const failed = jobs.some((job) => job.processingStatus === "FAILED");
				const pending = jobs.some(
					(job) => job.processingStatus === "NONE" || job.processingStatus === "PENDING",
				);
				let title = "Review still open";
				let text = "The recorded worker invocations ended; this review has not closed.";
				if (failed) {
					title = REVIEW_JOB_PROCESSING_DEFS.FAILED.label;
					text = `${REVIEW_JOB_PROCESSING_DEFS.FAILED.description} The review remains open.`;
				} else if (pending) {
					title = "Awaiting finalization";
					text = "The recorded worker invocations ended; the server has not finalized this review.";
				}
				return {
					kind: "waiting",
					stage: "review",
					title,
					text,
					tone: failed ? "warning" : "secondary",
					icon: HourglassIcon,
				};
			}
			const [running] = jobs
				.filter((job) => job.status === "RUNNING")
				.map((job) => job.startedAt)
				.filter((startedAt) => startedAt !== null)
				.sort((a, b) => a.getTime() - b.getTime());
			return {
				kind: "waiting",
				stage: "review",
				title: running === undefined ? "Review still open" : "Running",
				text:
					running === undefined
						? "The review remains open; no worker execution duration is known here."
						: `A worker invocation has been running for ${durationBetween(running, now)}.`,
				tone: "secondary",
				icon: HourglassIcon,
				span: "run",
			};
		}
		case "COMPLETE": {
			return undefined;
		}
	}
}

function silentOnPath(records: LineageRecords, review: ReviewRef, here: TraceNode | undefined) {
	if (here?.kind === "feedback") {
		return [];
	}
	const silent = silentNotMet(records);
	return here?.kind === "result"
		? silent.filter((entry) => entry.id === here.id)
		: silent.filter((entry) => entry.reviewId === review.id);
}

/** What of the path's feedback was lost: one piece, all of them, or some. */
function lossBottleneck(
	records: LineageRecords,
	review: ReviewRef,
	here: TraceNode | undefined,
): TraceBottleneck | undefined {
	const pieces = feedbackOnPath(records, review, here);
	const losses = pieces.map(feedbackLoss).filter((loss) => loss !== undefined);
	const reached = pieces.filter(reachedDeveloper).length;
	if (losses.length === 0) {
		const silent = silentOnPath(records, review, here);
		if (pieces.length > 0 || silent.length === 0) {
			return undefined;
		}
		return {
			kind: "lost",
			stage: "review",
			title: "Not told",
			text: `${silent.length === 1 ? "One practice was" : `${silent.length} practices were`} not met, but the review composed no feedback from ${silent.length === 1 ? "that observation" : "those observations"}, so the developer was not told.`,
			...NOT_TOLD,
		};
	}
	const [first] = losses;
	if (losses.length === 1 && first !== undefined && (here?.kind === "feedback" || reached === 0)) {
		return {
			kind: "lost",
			stage: "feedback",
			title: first.title,
			text: first.text,
			short: first.short,
			...lost(losses),
		};
	}
	if (reached === 0) {
		return {
			kind: "lost",
			stage: "feedback",
			title: "None arrived",
			text: `${countOf(losses.length, "piece", "pieces")} of feedback did not reach the developer: ${lossesByReason(losses)}.`,
			...lost(losses),
		};
	}
	return {
		kind: "lost",
		stage: "feedback",
		title: "Partly lost",
		text: `${reached} of ${pieces.length} pieces reached the developer; ${losses.length} did not: ${lossesByReason(losses)}.`,
		...lost(losses),
	};
}

function waitBottleneck(
	records: LineageRecords,
	review: ReviewRef,
	here: TraceNode | undefined,
): TraceBottleneck | undefined {
	const pieces = feedbackOnPath(records, review, here);
	if (pieces.some(reachedDeveloper)) {
		return undefined;
	}
	if (pieces.some((entry) => entry.state === "AWAITING_APPROVAL")) {
		return {
			kind: "waiting",
			stage: "feedback",
			title: "Awaiting approval",
			text: "It waits for a workspace admin to approve or reject it before anything is sent.",
			tone: "warning",
			icon: HourglassIcon,
		};
	}
	const ready = pieces.filter((entry) => entry.state === "READY");
	if (ready.some((entry) => entry.destination === "CONVERSATION")) {
		return {
			kind: "waiting",
			stage: "feedback",
			title: "Waiting for a conversation",
			text: "It reaches the developer only when they next talk with Hephaestus.",
			tone: "secondary",
			icon: HourglassIcon,
		};
	}
	if (ready.length > 0) {
		return {
			kind: "waiting",
			stage: "feedback",
			title: "Not sent yet",
			text: "It is ready and not delivered yet.",
			tone: "secondary",
			icon: HourglassIcon,
		};
	}
	// Conversation words only when every unconfirmed piece on the path is for a conversation.
	const unconfirmed =
		pieces.find((entry) => entry.state === "UNCONFIRMED" && entry.destination !== "CONVERSATION") ??
		pieces.find((entry) => entry.state === "UNCONFIRMED");
	if (unconfirmed !== undefined) {
		// The lineage carries no stop reason; an unconfirmed piece always stops for this one.
		const { label, description } = deliveryOutcome({
			...unconfirmed,
			stopReason: "DELIVERY_UNCONFIRMED",
		});
		return {
			kind: "waiting",
			stage: "effect",
			title: label,
			text: description,
			tone: "secondary",
			icon: HourglassIcon,
		};
	}
	return undefined;
}

const SPAN_STAGE: Record<MeasuredSpanKind, BottleneckStage> = {
	asked: "start",
	queue: "review",
	run: "review",
	delivery: "feedback",
};

function spanLength(entry: TraceSpan): number {
	return entry.to.getTime() - entry.from.getTime();
}

function timeBottleneck(
	lineage: ReviewLineage,
	records: LineageRecords,
	review: ReviewRef,
	here: TraceNode | undefined,
	detail: ReviewDetail | undefined,
): TraceBottleneck {
	const pieces = feedbackOnPath(records, review, here);
	const [slowest] = traceSpans(lineage, here, detail)
		.filter((entry) => entry.slow)
		.sort((a, b) => spanLength(b) - spanLength(a));
	if (slowest !== undefined) {
		return {
			kind: "slow",
			stage: SPAN_STAGE[slowest.kind],
			title: SLOW_TITLE[slowest.kind],
			text: SLOW_TEXT[slowest.kind](slowest.text),
			tone: "warning",
			icon: ClockIcon,
			span: slowest.kind,
		};
	}
	return {
		kind: "none",
		stage: "effect",
		title: "No bottleneck",
		text: noneText(pieces.length, pieces.filter(reachedDeveloper).length),
		// The ordinary case takes no colour, so the ones that do stand out.
		tone: "secondary",
		icon: CircleCheckIcon,
	};
}

export function traceBottleneck(
	lineage: ReviewLineage,
	here: TraceNode | undefined,
	detail?: ReviewDetail,
	now: Date = new Date(),
): TraceBottleneck {
	const records = lineageRecords(lineage);
	const review = traceReview(records, detail);
	if (review === undefined) {
		return {
			kind: "none",
			stage: "review",
			title: "Not known",
			text: "The record of this review is gone, so the trace cannot tell where time went.",
			tone: "outline",
			icon: CircleSlashIcon,
		};
	}
	return (
		reviewBottleneck(review, detail, now) ??
		lossBottleneck(records, review, here) ??
		waitBottleneck(records, review, here) ??
		timeBottleneck(lineage, records, review, here, detail)
	);
}

function noneText(pieces: number, reached: number): string {
	if (pieces === 0) {
		return "Nothing on this path had to be sent.";
	}
	return reached > 0
		? "Nothing was lost and no wait took half an hour or more."
		: "Nothing was lost on this path.";
}

const SLOW_TITLE: Record<MeasuredSpanKind, string> = {
	asked: "Slow start",
	queue: "Slow queue",
	run: "Slow run",
	delivery: "Slow delivery",
};

const SLOW_TEXT: Record<MeasuredSpanKind, (duration: string) => string> = {
	asked: (duration) => `The review was asked for ${duration} after the event that started it.`,
	queue: (duration) => `The review waited ${duration} in the queue for a worker.`,
	run: (duration) => `The review ran for ${duration}.`,
	delivery: (duration) =>
		`Its feedback reached the developer ${duration} after the review finished.`,
};

function plain(text: string): string {
	return text
		.toLowerCase()
		.replaceAll(/[.:;,]/gu, "")
		.trim();
}

/**
 * The line as it stands under a verdict: where the verdict already says the whole reason, only its
 * few words, so the two lines never say the same thing twice.
 */
export function bottleneckText(bottleneck: TraceBottleneck, verdict: string): string {
	if (bottleneck.short !== undefined && plain(verdict).includes(plain(bottleneck.text))) {
		return bottleneck.short;
	}
	return bottleneck.text;
}
