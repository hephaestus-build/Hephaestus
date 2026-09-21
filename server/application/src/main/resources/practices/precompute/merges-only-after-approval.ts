// Precompute FACTS for merges-only-after-approval: the merge as the record holds it — who merged,
// when, the provider's own review decision — one row per submitted review decision placed against
// the merge instant and the author, and each reviewer's last decision. The review decides whether
// an approval by someone else stood at the merge; the rows say what was submitted and when.
import {
	decisionRows,
	lastDecisionPerReviewer,
	mergeFacts,
	mergeRow,
	readReviewThreads,
} from "../lib/review.ts";
import type { DiffFile, PullRequestMetadata } from "../lib/types.ts";

export default async function mergesOnlyAfterApproval(
	_repoPath: string,
	_diffFiles: Map<string, DiffFile>,
	metadata: PullRequestMetadata,
	contextDir?: string,
) {
	const merge = mergeFacts(metadata);
	const record = await readReviewThreads(contextDir);
	const decisions = record?.decisions ?? [];
	const rows = decisionRows(decisions, merge);
	const last = decisionRows(lastDecisionPerReviewer(decisions), merge).map((row) => ({
		...row,
		pattern: "last decision by reviewer",
	}));
	const approvalsBeforeMergeByOthers = rows.filter(
		(r) =>
			r.flags.state === "APPROVED" &&
			r.flags.beforeMerge === true &&
			r.flags.isAuthor !== true &&
			r.flags.dismissed !== true &&
			r.flags.bot !== true,
	).length;
	const directions: string[] = [];
	if (!merge.merged) {
		directions.push("The pull request is not merged: the occasion did not arise.");
	} else if (record === null) {
		directions.push(
			"No review threads file was captured (review_threads.json): the decision record is not available here.",
		);
	} else {
		directions.push(
			`Merged${merge.mergedBy === undefined ? "" : ` by ${merge.mergedBy}`}${merge.mergedByIsAuthor ? " (the author)" : ""}${merge.mergedAt === undefined ? "" : ` at ${merge.mergedAt}`}; ${decisions.length} submitted decision(s), ${approvalsBeforeMergeByOthers} of them an undismissed APPROVED by someone other than the author before the merge; the last decision of each reviewer is listed apart.`,
		);
	}
	return {
		hints: [mergeRow(metadata, merge), ...rows, ...last],
		metrics: {
			merged: merge.merged ? 1 : 0,
			mergedByIsAuthor: merge.mergedByIsAuthor ? 1 : 0,
			decisions: decisions.length,
			approvalsBeforeMergeByOthers,
			decisionsByBots: decisions.filter((d) => d.bot).length,
			threadsFileAbsent: record === null ? 1 : 0,
		},
		directions,
	};
}
