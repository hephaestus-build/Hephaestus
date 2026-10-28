import { scm, expectKind } from "../kits/scm/index.ts";
import {
	decisionLeads,
	lastDecisionPerReviewer,
	mergeFacts,
	mergeLead,
	readReviewThreads,
	reviewsMergedWork,
} from "../kits/scm/review.ts";
import { DIRECTION_CHARS, oneLine } from "../lib/core/bounded-text.ts";
import { REVIEW_LEAD_LIMIT, type ReviewLead } from "../lib/core/contract.ts";
import { definePracticeScript } from "../lib/core/practice-script.ts";

export const LABELS = {
	merge: "The merge of the pull request: who merged it and when.",
	"aggregate-review-decision": "The aggregate review decision reported by the provider.",
	"review-decision":
		"A review decision that the record holds, placed against the time of the merge.",
	"last-decision-by-reviewer":
		"The last decision of a reviewer whose decision times are all known.",
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
		const record = await readReviewThreads(ctx.evidence);
		const decisions = record?.decisions ?? [];
		const rows = decisionLeads(decisions, merge, LABELS["review-decision"]);
		const last = decisionLeads(
			lastDecisionPerReviewer(decisions),
			merge,
			LABELS["last-decision-by-reviewer"],
		);
		const approvalsBeforeMergeByOthers = rows.filter(
			({ facts }) =>
				facts?.state === "APPROVED" &&
				facts.beforeMerge === true &&
				facts.isAuthor !== true &&
				facts.dismissed !== true &&
				facts.bot !== true,
		).length;
		const unknownDecisionTimes = decisions.filter((d) => d.submittedAt === undefined).length;
		const directions: string[] = [];
		if (record === null) {
			directions.push(
				"No review threads file was captured (review_threads.json): the decision record is not available here.",
			);
		} else {
			directions.push(
				oneLine(
					`Merged${merge.mergedBy === undefined ? "" : ` by ${merge.mergedBy}`}${merge.mergedByIsAuthor ? " (the author)" : ""}${merge.mergedAt === undefined ? "" : ` at ${merge.mergedAt}`}; ${String(decisions.length)} captured decision(s), ${String(approvalsBeforeMergeByOthers)} of them an undismissed APPROVED before the merge by an account other than the author's that is not marked bot.`,
					DIRECTION_CHARS,
				),
				"The last captured decision of each reviewer whose decision times are all known is listed apart.",
			);
			if (unknownDecisionTimes > 0) {
				directions.push(
					`${String(unknownDecisionTimes)} decision time(s) are unknown: the dated approval count is a lower bound, and no historical last decision is established for those reviewers.`,
				);
			}
			directions.push(
				"Read decisionHistoryComplete in review_threads.json: the order of the leads does not establish complete historical coverage. Apply the practice criteria to that qualification.",
			);
		}
		const aggregate =
			record?.reviewDecision === undefined
				? []
				: [
						{
							at: { record: "review_threads.json" },
							label: LABELS["aggregate-review-decision"],
							facts: { reviewDecision: record.reviewDecision },
						} satisfies ReviewLead,
					];
		const leads = [mergeLead(domain.item, merge, LABELS.merge), ...aggregate, ...rows, ...last];
		return ctx.review({
			leads: leads.slice(0, REVIEW_LEAD_LIMIT),
			limitations:
				leads.length > REVIEW_LEAD_LIMIT
					? [
							{
								what: "review decision leads",
								count: leads.length - REVIEW_LEAD_LIMIT,
								reason: "selection",
							},
						]
					: [],
			facts: {
				mergedByIsAuthor: merge.mergedByIsAuthor ? 1 : 0,
				decisions: decisions.length,
				unknownDecisionTimes,
				approvalsBeforeMergeByOthers,
				decisionsByBots: decisions.filter((d) => d.bot).length,
				threadsFileAbsent: record === null ? 1 : 0,
			},
			directions,
		});
	},
});
