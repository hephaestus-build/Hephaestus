// Precompute FACTS for merged-past-unresolved-review-threads: the merge as the record holds it —
// who merged, when — and one row per review thread the record does not show resolved by the merge,
// with where it is anchored, when it was opened and whether the provider calls it outdated. The review
// reads each thread against the merge; the rows say what the record holds, not whether the merge was owed.
import {
	dated,
	mergeFacts,
	mergeRow,
	readReviewThreads,
	resolvedByMerge,
	unresolvedThreadRows,
} from "../lib/review.ts";
import type { DiffFile, PullRequestMetadata } from "../lib/types.ts";

export default async function mergedPastUnresolvedReviewThreads(
	_repoPath: string,
	_diffFiles: Map<string, DiffFile>,
	metadata: PullRequestMetadata,
	contextDir: string | undefined,
	_changeDir: string | undefined,
	contextReference: string,
) {
	const merge = mergeFacts(metadata);
	const mergeUndated = merge.merged && !dated(merge.mergedAt);
	const record = await readReviewThreads(contextDir);
	const threads = record?.threads ?? [];
	const unresolved = unresolvedThreadRows(threads, contextReference, merge);
	const directions: string[] = [];
	if (!merge.merged) {
		directions.push("The pull request is not merged: the occasion did not arise.");
	} else if (record === null) {
		directions.push(
			"No review threads file was captured (review_threads.json): the thread record is not available here.",
		);
	} else {
		directions.push(
			`Merged${merge.mergedBy === undefined ? "" : ` by ${merge.mergedBy}`}${merge.mergedByIsAuthor ? " (the author)" : ""}; ${threads.length} thread(s) captured, ${unresolved.length} candidate thread(s) — currently not marked RESOLVED, resolved after the merge, or resolved at a time the record does not establish — one row each.${mergeUndated ? " The record gives no usable merge time, so no resolution is placed before or after the merge." : ""} These are not proven open at merge: compare opening and resolution times, and treat an unknown historical order as UNDETERMINED. Read each thread's comments in comments.json by its id (the comments' \`thread\`) before deciding what it asked.`,
		);
	}
	return {
		hints: [mergeRow(metadata, merge, contextReference), ...unresolved],
		metrics: {
			merged: merge.merged ? 1 : 0,
			mergedByIsAuthor: merge.mergedByIsAuthor ? 1 : 0,
			threads: threads.length,
			candidateThreads: unresolved.length,
			resolutionTimeUnknown: unresolved.filter(
				(h) =>
					h.flags.state === "RESOLVED" &&
					typeof h.flags.resolvedAt === "string" &&
					!dated(h.flags.resolvedAt),
			).length,
			// Undated, the merge places no resolution before it, so the count is left out rather than zero.
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
	};
}
