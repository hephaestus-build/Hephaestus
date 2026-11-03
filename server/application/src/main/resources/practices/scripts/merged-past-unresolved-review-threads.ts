import {
	definePracticeScript,
	type ReviewLead,
	type PracticeScriptContext,
} from "@hephaestus/practice-scripts";
import { dated, later, scm, type ReviewThread } from "@hephaestus/practice-scripts/scm";

const REVIEW_LEAD_LIMIT = 200;

export const LABELS = {
	merge:
		"The merge of the pull request: who merged it, when, and the review decision of the provider.",
	"unresolved-thread": "A review thread that the record does not show as resolved.",
	"thread-resolved-after-the-merge":
		"A review thread that the record shows as resolved after the merge.",
	"resolved-thread-resolution-time-unknown":
		"A resolved review thread with no usable resolution time.",
	"resolved-thread-merge-time-unknown":
		"A resolved review thread of a merge with no usable merge time.",
	"resolved-thread-both-times-unknown":
		"A resolved review thread with no usable resolution time and no usable merge time.",
};

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request");
		if (!reviewsMergedWork(ctx.review)) {
			return ctx.review({
				leads: [],
				directions: [
					"The pull request is not merged. This practice reviews the merge, so it has nothing to check yet.",
				],
			});
		}
		const merge = mergeFacts(work.item);
		const mergeUndated = !dated(merge.mergedAt);
		const record = await work.reviewRecord();
		const threads = record?.threads ?? [];
		const candidates = candidateThreads(threads, merge);
		const selected = candidates.slice(0, REVIEW_LEAD_LIMIT - 1);
		const directions: string[] = [];
		if (record === null) {
			directions.push(
				"No review threads file was captured (review_threads.json): the thread record is not available here.",
			);
		} else {
			directions.push(
				`Merged${merge.mergedBy === undefined ? "" : ` by ${merge.mergedBy}`}${merge.mergedByIsAuthor ? " (the author)" : ""}; ${String(threads.length)} thread(s) captured, ${String(candidates.length)} candidate thread(s), ${String(selected.length)} thread lead(s).`,
				"A candidate thread is not marked RESOLVED now, was resolved after the merge, or was resolved at a time that the record does not establish.",
				...(mergeUndated
					? [
							"The record gives no usable merge time, so no resolution is placed before or after the merge.",
						]
					: []),
				"These are not proven open at merge: compare opening and resolution times; where the record cannot settle that order, name the missing date and decide as the criteria say.",
				"Read each thread's comments in comments.json by its id (the comments' `thread`) before deciding what it asked.",
			);
		}
		return ctx.review({
			leads: [
				mergeLead(merge, LABELS.merge),
				...selected.map((thread) => threadLead(thread, merge, LABELS)),
			],
			limitations:
				candidates.length > selected.length
					? [
							{
								what: "review thread leads",
								count: candidates.length - selected.length,
								reason: "selection",
							},
						]
					: [],
			facts: {
				mergedByIsAuthor: merge.mergedByIsAuthor ? 1 : 0,
				threads: threads.length,
				candidateThreads: candidates.length,
				resolutionTimeUnknown: candidates.filter(
					(t) => t.state === "RESOLVED" && !dated(t.resolvedAt),
				).length,
				// An undated merge cannot prove whether resolution happened before it.
				...(mergeUndated
					? {}
					: {
							resolvedBeforeMerge: threads.filter(
								(t) => t.state === "RESOLVED" && resolvedByMerge(t, merge),
							).length,
						}),
				threadsFileAbsent: record === null ? 1 : 0,
			},
			directions,
		});
	},
});

/**
 * Whether a practice that only the merge moment brings into a review reads a merged pull request:
 * at that event, or when the review state that the review recorded is MERGED.
 */
function reviewsMergedWork(review: PracticeScriptContext["review"]): boolean {
	return review.event?.name === "scm.pull_request.merged" || review.event?.state.state === "MERGED";
}

interface MergeFacts {
	mergedAt?: string;
	mergedBy?: string;
	mergedByIsAuthor: boolean;
	author?: string;
}

interface MergeRecord {
	merged_at?: string | null;
	merged_by?: string | null;
	author?: string | null;
}

function mergeFacts(record: MergeRecord): MergeFacts {
	const mergedAt = present(record.merged_at);
	const mergedBy = present(record.merged_by);
	const author = present(record.author);
	return {
		mergedAt,
		mergedBy,
		mergedByIsAuthor: mergedBy !== undefined && mergedBy === author,
		author,
	};
}

function mergeLead(merge: MergeFacts, label: string): ReviewLead {
	return {
		at: { record: "metadata.json" },
		label,
		facts: {
			mergedBy: merge.mergedBy ?? "",
			mergedByIsAuthor: merge.mergedByIsAuthor,
			mergedAt: merge.mergedAt ?? "",
			author: merge.author ?? "",
		},
	};
}

/** Whether the record dates a thread's resolution at or before a dated merge. */
function resolvedByMerge(thread: ReviewThread, merge: MergeFacts): boolean {
	return (
		dated(thread.resolvedAt) && dated(merge.mergedAt) && !later(thread.resolvedAt, merge.mergedAt)
	);
}

/**
 * The threads to review against the merge: currently unresolved threads, and resolved threads the
 * record does not date as resolved by the merge — every one of them when the merge itself is
 * undated. A resolved thread opened after a dated merge was not open at it; opening times still
 * determine whether every other thread existed at the merge.
 */
function candidateThreads(threads: readonly ReviewThread[], merge: MergeFacts): ReviewThread[] {
	return threads.filter(
		(t) =>
			t.state !== "RESOLVED" || (!resolvedByMerge(t, merge) && !later(t.createdAt, merge.mergedAt)),
	);
}

type ThreadKind =
	| "unresolved-thread"
	| "thread-resolved-after-the-merge"
	| "resolved-thread-resolution-time-unknown"
	| "resolved-thread-merge-time-unknown"
	| "resolved-thread-both-times-unknown";

function threadKind(t: ReviewThread, merge: MergeFacts): ThreadKind {
	if (t.state !== "RESOLVED") {
		return "unresolved-thread";
	}
	if (dated(merge.mergedAt)) {
		return dated(t.resolvedAt)
			? "thread-resolved-after-the-merge"
			: "resolved-thread-resolution-time-unknown";
	}
	return dated(t.resolvedAt)
		? "resolved-thread-merge-time-unknown"
		: "resolved-thread-both-times-unknown";
}

function threadLead(
	t: ReviewThread,
	merge: MergeFacts,
	labels: Readonly<Record<ThreadKind, string>>,
): ReviewLead {
	return {
		at: { record: "review_threads.json" },
		label: labels[threadKind(t, merge)],
		facts: {
			id: t.id ?? 0,
			state: t.state,
			createdAt: t.createdAt ?? "",
			resolvedAt: t.resolvedAt ?? "",
			resolvedBy: t.resolvedBy ?? "",
			path: t.path ?? "",
			line: t.line ?? 0,
			outdated: t.outdated === true,
		},
	};
}

const present = (value: string | null | undefined) =>
	value === null || value === undefined || value === "" ? undefined : value;
