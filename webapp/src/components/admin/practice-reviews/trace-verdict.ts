import type { ItemEvent, ItemRef, ReviewLineage, ReviewRef } from "@/api/types.gen";
import type { BadgeVariant } from "@/components/common/status-def";
import { FEEDBACK_LOCATION_DEFS } from "@/components/practice-vocabulary/feedback-location-defs";
import {
	ITEM_EVENT_REASON_SENTENCES,
	ITEM_EVENT_STATE_DEFS,
} from "@/components/practice-vocabulary/item-event-defs";
import { durationBetween } from "@/lib/dates";

import {
	countOf,
	feedbackLocation,
	feedbackOf,
	type LineageFeedback,
	type LineageRecords,
	type LineageResult,
	lineageRecords,
	lowerFirst,
	reachedDeveloper,
	silentNotMet,
} from "./lineage-chain";
import { chainReview, ownResults, type TraceNode } from "./review-trace";

/**
 * How the path of the record in front ends, as the story's last step says it:
 *
 * - `delivered`: it reached the developer and stands.
 * - `lost`: it did not arrive, or no longer stands: withheld, rejected, replaced, failed.
 * - `open`: it is still on its way or waits for an admin; a review still runs.
 * - `none`: nothing was sent, and nothing had to be: a met result, a review with nothing to say.
 */
export type TraceEndKind = "delivered" | "lost" | "open" | "none";

export interface TraceEnd {
	kind: TraceEndKind;
	label: string;
	reason: string;
	/** When the path ended, where the record says. */
	at?: Date;
	/** Open: since when, where the record says. */
	since?: Date;
}

export interface TraceVerdict {
	/** One sentence on top of the story that says what became of the record in front. */
	sentence: string;
	/** The end step of the record's path. */
	end: TraceEnd;
}

/** The end step's label for each way a path ends. */
export const TRACE_END_LABELS: Record<TraceEndKind, string> = {
	delivered: "Delivered",
	lost: "Did not arrive",
	open: "Open",
	none: "No feedback",
};

/** The end step's dot for each way a path ends; `none` is hollow. */
export const TRACE_END_TONES: Record<TraceEndKind, BadgeVariant> = {
	delivered: "success",
	lost: "warning",
	open: "secondary",
	none: "outline",
};

/** Which end of several pieces names the path: the best thing that happened to any of them. */
const END_RANK: Record<TraceEndKind, number> = {
	delivered: 0,
	open: 1,
	lost: 2,
	none: 3,
};

function end(
	kind: TraceEndKind,
	reason: string,
	times: Pick<TraceEnd, "at" | "since"> & { label?: string } = {},
): TraceEnd {
	const { label = TRACE_END_LABELS[kind], ...at } = times;
	return { kind, label, reason, ...at };
}

// ------------------------------------------------------------------------------------------------
// One piece of feedback
// ------------------------------------------------------------------------------------------------

interface FeedbackFate {
	end: TraceEnd;
	clause: readonly [singular: string, plural: string];
	phrase: string;
}

const READY_FOR: Record<
	LineageFeedback["destination"],
	{ end: string; sentence: string; phrase: string }
> = {
	ON_WORK: {
		end: "it is ready to be posted on the work",
		sentence: "it is ready to be posted on the work",
		phrase: "ready to be posted on the work",
	},
	PRACTICE_PAGE: {
		end: "it is ready for the developer’s practice pages",
		sentence: "it is ready for their practice pages",
		phrase: "ready for their practice pages",
	},
	CONVERSATION: {
		end: "it is ready for the next conversation with Hephaestus",
		sentence: "it is ready for their next conversation with Hephaestus",
		phrase: "ready for a conversation",
	},
};

function feedbackFate(feedback: LineageFeedback, item: ItemRef | undefined): FeedbackFate {
	const at = feedback.deliveredAt ?? undefined;
	if (feedback.withdrawn) {
		const delivered = reachedDeveloper(feedback);
		return {
			end: end(
				"lost",
				delivered
					? "an admin withdrew it after it was delivered"
					: "an admin withdrew it before it reached the developer",
			),
			clause: delivered
				? ["was delivered, then withdrawn", "were delivered, then withdrawn"]
				: [
						"was withdrawn before it reached the developer",
						"were withdrawn before they reached the developer",
					],
			phrase: "withdrawn by an admin",
		};
	}
	switch (feedback.state) {
		case "REJECTED": {
			return {
				end: end("lost", "an admin rejected it"),
				clause: ["was rejected by an admin", "were rejected by an admin"],
				phrase: "rejected by an admin",
			};
		}
		case "AWAITING_APPROVAL": {
			return {
				end: end("open", "it awaits an admin’s approval"),
				clause: ["awaits approval", "await approval"],
				phrase: "awaiting approval",
			};
		}
		case "READY": {
			const ready = READY_FOR[feedback.destination];
			return {
				end: end("open", ready.end),
				clause: [`is ${ready.phrase}`, `are ${ready.phrase}`],
				phrase: ready.phrase,
			};
		}
		case "REPLACED": {
			return {
				end: end("lost", "newer feedback replaced it"),
				clause: ["was replaced by newer feedback", "were replaced by newer feedback"],
				phrase: "replaced by newer feedback",
			};
		}
		case "FAILED": {
			if (at !== undefined) {
				return {
					end: end(
						"delivered",
						"part of it reached the developer, and another part failed to post",
						{
							at,
							label: "Partly delivered",
						},
					),
					clause: ["was partly delivered", "were partly delivered"],
					phrase: "partly delivered",
				};
			}
			return {
				end: end("lost", "it failed to deliver"),
				clause: ["failed to deliver", "failed to deliver"],
				phrase: "failed to deliver",
			};
		}
		case "UNCONFIRMED": {
			if (at !== undefined && feedback.destination === "ON_WORK") {
				return {
					end: end(
						"delivered",
						"part of it reached the developer, and the rest is not confirmed yet",
						{
							at,
							label: "Partly delivered",
						},
					),
					clause: ["was partly delivered", "were partly delivered"],
					phrase: "partly delivered",
				};
			}
			return {
				end: end("none", "no record confirms that it reached the developer", {
					label: "Not known",
				}),
				clause: ["is unconfirmed", "are unconfirmed"],
				phrase: "unconfirmed",
			};
		}
		case "WITHHELD": {
			return at === undefined
				? {
						end: end("lost", "it was withheld"),
						clause: ["was withheld", "were withheld"],
						phrase: "withheld",
					}
				: {
						end: end("lost", "it was delivered, then withheld", { at }),
						clause: ["was delivered, then withheld", "were delivered, then withheld"],
						phrase: "delivered, then withheld",
					};
		}
		case "DELIVERED": {
			const place = lowerFirst(FEEDBACK_LOCATION_DEFS[feedbackLocation(feedback, item)].label);
			return {
				end: end("delivered", `it reached the developer ${place}`, { at }),
				clause: ["was delivered", "were delivered"],
				phrase: "delivered",
			};
		}
	}
}

function feedbackSentence(feedback: LineageFeedback, item: ItemRef | undefined): string {
	const place = lowerFirst(FEEDBACK_LOCATION_DEFS[feedbackLocation(feedback, item)].label);
	const reached = `This feedback reached the developer ${place}`;
	if (feedback.withdrawn) {
		return reachedDeveloper(feedback)
			? `${reached}, then an admin withdrew it.`
			: "This feedback did not reach the developer: an admin withdrew it first.";
	}
	switch (feedback.state) {
		case "REJECTED": {
			return "This feedback did not reach the developer: an admin rejected it.";
		}
		case "AWAITING_APPROVAL": {
			return "This feedback has not reached the developer yet: it awaits an admin’s approval.";
		}
		case "READY": {
			return `This feedback has not reached the developer yet: ${READY_FOR[feedback.destination].sentence}.`;
		}
		case "REPLACED": {
			return feedback.deliveredAt === null
				? "This feedback did not reach the developer: newer feedback replaced it."
				: `${reached}, then newer feedback replaced it.`;
		}
		case "FAILED": {
			return feedback.deliveredAt === null
				? "This feedback did not reach the developer: it failed to deliver."
				: `Part of this feedback reached the developer ${place}, and another part failed to post.`;
		}
		case "UNCONFIRMED": {
			return feedback.deliveredAt === null || feedback.destination !== "ON_WORK"
				? "This feedback was sent, but no record confirms that it reached the developer."
				: `Part of this feedback reached the developer ${place}, and the rest is not confirmed yet.`;
		}
		case "WITHHELD": {
			return feedback.deliveredAt === null
				? "This feedback did not reach the developer: it was withheld."
				: `${reached}, then was withheld.`;
		}
		case "DELIVERED": {
			return `${reached}.`;
		}
	}
}

const NUMBER_WORDS = ["no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"];

/** "it", "both", "all 5": a count that is every piece there is. */
function wholeCount(count: number): string {
	if (count === 1) {
		return "it";
	}
	return count === 2 ? "both" : `all ${count}`;
}

/** "one", "two" … "nine", then the number: a count that opens a clause. */
function countWord(count: number): string {
	return NUMBER_WORDS[count] ?? String(count);
}

/** "one was delivered and resolved by the work, and one was withheld", in the order the pieces came. */
function fatesClause(fates: readonly FeedbackFate[]): string {
	const groups = new Map<string, { fate: FeedbackFate; count: number }>();
	for (const fate of fates) {
		const key = fate.clause[0];
		const group = groups.get(key);
		if (group === undefined) {
			groups.set(key, { fate, count: 1 });
		} else {
			group.count += 1;
		}
	}
	if (groups.size > 3) {
		return kindsClause(fates);
	}
	const parts = [...groups.values()].map(({ fate, count }) => {
		const clause = fate.clause[count === 1 ? 0 : 1];
		return count === fates.length
			? `${wholeCount(count)} ${clause}`
			: `${countWord(count)} ${clause}`;
	});
	return joinAnd(parts);
}

const KIND_CLAUSE: Record<TraceEndKind, readonly [singular: string, plural: string]> = {
	delivered: ["was delivered", "were delivered"],
	open: ["is still open", "are still open"],
	lost: ["did not arrive", "did not arrive"],
	none: ["has no known end", "have no known end"],
};

const KIND_PHRASE: Record<TraceEndKind, string> = {
	delivered: "delivered",
	open: "open",
	lost: "did not arrive",
	none: "with no known end",
};

const KINDS: readonly TraceEndKind[] = ["delivered", "open", "lost", "none"];

function kindsClause(fates: readonly FeedbackFate[]): string {
	return joinAnd(
		KINDS.map((kind) => ({ kind, count: fates.filter((fate) => fate.end.kind === kind).length }))
			.filter(({ count }) => count > 0)
			.map(({ kind, count }) => `${countWord(count)} ${KIND_CLAUSE[kind][count === 1 ? 0 : 1]}`),
	);
}

function joinAnd(parts: readonly string[]): string {
	if (parts.length <= 1) {
		return parts.join("");
	}
	return `${parts.slice(0, -1).join(", ")}, and ${parts.at(-1) ?? ""}`;
}

/** The end of several pieces' path: the best end any of them reached, with every end counted. */
function endOfMany(fates: readonly FeedbackFate[]): TraceEnd {
	const [only] = fates;
	if (only === undefined) {
		return end("none", "no feedback is listed");
	}
	if (fates.length === 1) {
		return only.end;
	}
	let best = only;
	for (const fate of fates) {
		if (END_RANK[fate.end.kind] < END_RANK[best.end.kind]) {
			best = fate;
		}
	}
	const counts = new Map<string, number>();
	for (const fate of fates) {
		counts.set(fate.phrase, (counts.get(fate.phrase) ?? 0) + 1);
	}
	const reason =
		counts.size > 3
			? KINDS.map((kind) => ({
					kind,
					count: fates.filter((fate) => fate.end.kind === kind).length,
				}))
					.filter(({ count }) => count > 0)
					.map(({ kind, count }) => `${count} ${KIND_PHRASE[kind]}`)
					.join(", ")
			: [...counts.entries()].map(([phrase, count]) => `${count} ${phrase}`).join(", ");
	return end(best.end.kind, `${countOf(fates.length, "piece", "pieces")} of feedback: ${reason}`);
}

function isRunning(review: ReviewRef | undefined): boolean {
	return review?.status === "QUEUED" || review?.status === "RUNNING";
}

function resultVerdict(records: LineageRecords, result: LineageResult): TraceVerdict {
	const written = feedbackOf(records.feedback, result.id);
	if (written.length > 0) {
		const fates = written.map((entry) => feedbackFate(entry, records.item));
		return {
			sentence: `This observation led to ${countOf(written.length, "piece", "pieces")} of feedback; ${fatesClause(fates)}.`,
			end: endOfMany(fates),
		};
	}
	const review = records.reviews.find((entry) => entry.id === result.reviewId);
	switch (result.outcome) {
		case "NOT_MET": {
			if (isRunning(review)) {
				return {
					sentence: "This observation has led to no feedback yet, and the review remains open.",
					end: end("open", "the review remains open", { since: review?.createdAt }),
				};
			}
			if (review?.status === "FAILED" || review?.status === "CANCELLED") {
				return {
					sentence:
						"This observation led to no feedback, because the review stopped before it composed any.",
					end: end("lost", "the review stopped before it composed feedback", {
						at: review.completedAt ?? undefined,
					}),
				};
			}
			return {
				sentence:
					"The practice was not met, but no feedback was written, so the developer was not told.",
				end: end("lost", "no feedback was written from it"),
			};
		}
		case "MET": {
			return {
				sentence: "The practice was met, so no feedback was written.",
				end: end("none", "it was met, so nothing had to be sent"),
			};
		}
		case "DOES_NOT_APPLY": {
			return {
				sentence: "The practice did not apply to the work, so no feedback was written.",
				end: end("none", "the practice did not apply"),
			};
		}
		case "COULD_NOT_DETERMINE": {
			return {
				sentence:
					"The review could not determine whether the practice was met, so no feedback was written.",
				end: end("none", "the review could not tell"),
			};
		}
	}
}

// ------------------------------------------------------------------------------------------------
// A review
// ------------------------------------------------------------------------------------------------

const STOPPED: Partial<Record<ReviewRef["status"], string>> = {
	FAILED: "failed",
	CANCELLED: "was cancelled",
};

function reviewVerdict(records: LineageRecords): TraceVerdict {
	const review = chainReview(records);
	if (review === undefined && records.reviews.length > 1) {
		return {
			sentence: `This work has ${countOf(records.reviews.length, "review", "reviews")}, so open one to see what it did.`,
			end: end("none", "each review tells what it did", { label: "Several reviews" }),
		};
	}
	if (review === undefined) {
		return {
			sentence: "The record of this review is gone, so this view cannot tell what it did.",
			end: end("none", "the record of the review is gone", { label: "Not known" }),
		};
	}
	const results = ownResults(records);
	const recordedIds = new Set(results.map((entry) => entry.id));
	const own = records.feedback.filter((entry) => entry.basis.some((id) => recordedIds.has(id)));
	const fates = own.map((entry) => feedbackFate(entry, records.item));
	const recorded = countOf(results.length, "observation", "observations");
	const took =
		review.completedAt === null ? undefined : durationBetween(review.createdAt, review.completedAt);

	if (review.status === "QUEUED") {
		return {
			sentence: "This review waits in the queue and has recorded nothing yet.",
			end: end("open", "the review waits in the queue", { since: review.createdAt }),
		};
	}
	if (review.status === "RUNNING") {
		return {
			sentence:
				results.length === 0
					? "This review remains open and has recorded no observation yet."
					: `This review remains open and has recorded ${recorded} so far.`,
			end: end("open", "the review remains open", { since: review.createdAt }),
		};
	}
	const how = STOPPED[review.status];
	if (how !== undefined) {
		const after = took === undefined ? "" : ` after ${took}`;
		let what = `and composed ${countOf(own.length, "piece", "pieces")} of feedback`;
		if (results.length === 0) {
			what = "with nothing recorded";
		} else if (own.length === 0) {
			what = `and recorded ${recorded}, but composed no feedback`;
		}
		return {
			sentence: `This review ${how}${after} ${what}.`,
			end:
				own.length === 0
					? end("lost", `the review ${how} before it composed feedback`, {
							at: review.completedAt ?? undefined,
						})
					: endOfMany(fates),
		};
	}
	const after = took === undefined ? "" : ` after ${took}`;
	const ran = review.status === "PARTIAL" ? `closed as partial${after}` : `completed${after}`;
	if (results.length === 0) {
		return {
			sentence: `This review ${ran} and recorded no observation.`,
			end: end("none", "the review recorded no observation"),
		};
	}
	if (own.length === 0) {
		const silent = silentNotMet({ results, feedback: records.feedback }).length;
		return {
			sentence: `This review ${ran} and recorded ${recorded}, but composed no feedback.`,
			end:
				silent > 0
					? end(
							"lost",
							`${countOf(silent, "not-met observation", "not-met observations")} led to no feedback`,
						)
					: end("none", "no feedback was composed"),
		};
	}
	const reached = own.filter(reachedDeveloper).length;
	let reachedText = `${reached} reached the developer`;
	if (reached === own.length) {
		reachedText =
			own.length === 1 ? "it reached the developer" : `all ${own.length} reached the developer`;
	} else if (reached === 0) {
		reachedText = "none reached the developer";
	}
	return {
		sentence: `This review ${ran} and composed ${countOf(own.length, "piece", "pieces")} of feedback; ${reachedText}.`,
		end: endOfMany(fates),
	};
}

// ------------------------------------------------------------------------------------------------
// The record in front
// ------------------------------------------------------------------------------------------------

export function traceVerdict(lineage: ReviewLineage, here: TraceNode | undefined): TraceVerdict {
	const records = lineageRecords(lineage);
	if (here?.kind === "feedback") {
		const feedback = records.feedback.find((entry) => entry.id === here.id);
		if (feedback !== undefined) {
			return {
				sentence: feedbackSentence(feedback, records.item),
				end: feedbackFate(feedback, records.item).end,
			};
		}
	}
	if (here?.kind === "result") {
		const result = records.results.find((entry) => entry.id === here.id);
		if (result !== undefined) {
			return resultVerdict(records, result);
		}
	}
	return reviewVerdict(records);
}

// ------------------------------------------------------------------------------------------------
// Work no review started on
// ------------------------------------------------------------------------------------------------

export function unreviewedVerdict(
	event: ItemEvent,
	practicesEnabled: boolean | undefined,
): TraceVerdict {
	const staleOff = event.reason === "PRACTICES_DISABLED" && practicesEnabled === true;
	const reason =
		event.reason === null || staleOff ? undefined : ITEM_EVENT_REASON_SENTENCES[event.reason];
	switch (event.state) {
		case "WAITING": {
			return {
				sentence: "No review has started on this work yet: it waits before a review starts.",
				end: end("open", reason ?? "it waits before a review starts", {
					label: "Waiting",
					since: event.occurredAt,
				}),
			};
		}
		case "STARTED": {
			return {
				sentence: "A review started on this work.",
				end: end("open", "a review started", { label: "Review started", at: event.occurredAt }),
			};
		}
		case "RECORDED":
		case "WITHHELD":
		case "EXPIRED": {
			break;
		}
	}
	const why =
		reason ??
		(staleOff
			? "Practice review was turned off for this workspace when it happened. It is on now."
			: ITEM_EVENT_STATE_DEFS[event.state].description);
	return {
		sentence: `No review started on this work: ${lowerFirst(firstSentence(why))}.`,
		end: end("lost", why, { label: "No review started", at: event.occurredAt }),
	};
}

/** The first sentence of a reason, without its full stop, for a sentence of the trace's own. */
function firstSentence(text: string): string {
	return (text.split(/(?<=\.)\s/u)[0] ?? text).replace(/\.$/u, "");
}
