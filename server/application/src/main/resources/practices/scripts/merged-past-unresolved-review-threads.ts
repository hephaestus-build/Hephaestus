import { scm, expectKind } from "../kits/scm/index.ts";
import {
	candidateThreads,
	dated,
	mergeFacts,
	mergeLead,
	readReviewThreads,
	resolvedByMerge,
	reviewsMergedWork,
	threadLead,
} from "../kits/scm/review.ts";
import { REVIEW_LEAD_LIMIT } from "../lib/core/contract.ts";
import { definePracticeScript } from "../lib/core/practice-script.ts";

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
		const domain = scm(ctx);
		expectKind(domain, "scm.pull_request");
		if (!reviewsMergedWork(ctx.review)) {
			return ctx.review({
				leads: [],
				directions: [
					"The pull request is not merged. This practice reviews the merge, so it has nothing to check yet.",
				],
			});
		}
		const merge = mergeFacts(domain.item);
		const mergeUndated = !dated(merge.mergedAt);
		const record = await readReviewThreads(ctx.evidence);
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
