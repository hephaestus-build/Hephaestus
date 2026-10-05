// Precompute FACTS for merges-only-after-approval: the merge as the record holds it — who merged,
// when, the provider's own review decision — captured decision rows placed against the merge instant.
// Currentness and history coverage remain separate from this advisory ordering.
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
	contextDir: string | undefined,
	_changeDir: string | undefined,
	contextReference: string,
) {
	const merge = mergeFacts(metadata);
	const record = await readReviewThreads(contextDir);
	const decisions = record?.decisions ?? [];
	const rows = decisionRows(decisions, merge, contextReference);
	const last = decisionRows(lastDecisionPerReviewer(decisions), merge, contextReference).map(
		(row) => ({
			...row,
			pattern: "last decision by reviewer",
		}),
	);
	const approvalsBeforeMergeByOthers = rows.filter(
		(r) =>
			r.flags.state === "APPROVED" &&
			r.flags.beforeMerge === true &&
			r.flags.isAuthor !== true &&
			r.flags.dismissed !== true &&
			r.flags.bot !== true,
	).length;
	const unknownDecisionTimes = decisions.filter((d) => d.submittedAt === undefined).length;
	const directions: string[] = [];
	if (!merge.merged) {
		directions.push("The pull request is not merged: the occasion did not arise.");
	} else if (record === null) {
		directions.push(
			"No review threads file was captured (review_threads.json): the decision record is not available here.",
		);
	} else {
		directions.push(
			`Merged${merge.mergedBy === undefined ? "" : ` by ${merge.mergedBy}`}${merge.mergedByIsAuthor ? " (the author)" : ""}${merge.mergedAt === undefined ? "" : ` at ${merge.mergedAt}`}; ${decisions.length} captured decision(s), ${approvalsBeforeMergeByOthers} of them an undismissed APPROVED before the merge by an account other than the author's that is not marked bot; the last captured decision of each reviewer whose decision times are all known is listed apart.`,
		);
		if (unknownDecisionTimes > 0) {
			directions.push(
				`${unknownDecisionTimes} decision time(s) are unknown: the dated approval count is a lower bound, and no historical last decision is established for those reviewers.`,
			);
		}
		directions.push(
			"Read decisionHistoryComplete in review_threads.json: row ordering does not establish complete historical coverage. Apply the practice criteria to that qualification.",
		);
	}
	return {
		hints: [mergeRow(metadata, merge, contextReference), ...rows, ...last],
		metrics: {
			merged: merge.merged ? 1 : 0,
			mergedByIsAuthor: merge.mergedByIsAuthor ? 1 : 0,
			decisions: decisions.length,
			unknownDecisionTimes,
			approvalsBeforeMergeByOthers,
			decisionsByBots: decisions.filter((d) => d.bot).length,
			threadsFileAbsent: record === null ? 1 : 0,
		},
		directions,
	};
}
