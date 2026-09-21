// Precompute FACTS for merged-past-unresolved-review-threads: the merge as the record holds it —
// who merged, when — and one row per review thread the record does not mark RESOLVED, with where
// it is anchored, when it was opened and whether the provider calls it outdated. The review reads
// each thread against the merge; the rows say what the record holds, not whether the merge was owed.
import { mergeFacts, mergeRow, readReviewThreads, unresolvedThreadRows } from "../lib/review.ts";
import type { DiffFile, PullRequestMetadata } from "../lib/types.ts";

export default async function mergedPastUnresolvedReviewThreads(
	_repoPath: string,
	_diffFiles: Map<string, DiffFile>,
	metadata: PullRequestMetadata,
	contextDir?: string,
) {
	const merge = mergeFacts(metadata);
	const record = await readReviewThreads(contextDir);
	const threads = record?.threads ?? [];
	const unresolved = unresolvedThreadRows(threads);
	const directions: string[] = [];
	if (!merge.merged) {
		directions.push("The pull request is not merged: the occasion did not arise.");
	} else if (record === null) {
		directions.push(
			"No review threads file was captured (review_threads.json): the thread record is not available here.",
		);
	} else {
		directions.push(
			`Merged${merge.mergedBy === undefined ? "" : ` by ${merge.mergedBy}`}${merge.mergedByIsAuthor ? " (the author)" : ""}; ${threads.length} thread(s) captured, ${unresolved.length} not marked RESOLVED, one row each. Read each unresolved thread's comments in comments.json by its id (the comments' \`thread\`) before deciding what it asked.`,
		);
	}
	return {
		hints: [mergeRow(metadata, merge), ...unresolved],
		metrics: {
			merged: merge.merged ? 1 : 0,
			mergedByIsAuthor: merge.mergedByIsAuthor ? 1 : 0,
			threads: threads.length,
			unresolvedThreads: unresolved.length,
			resolvedThreads: threads.length - unresolved.length,
			threadsFileAbsent: record === null ? 1 : 0,
		},
		directions,
	};
}
