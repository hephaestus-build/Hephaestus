import {
	definePracticeScript,
	type ReviewLead,
	type PracticeScriptContext,
} from "@hephaestus/practice-scripts";
import { later, scm, type ReviewDecision } from "@hephaestus/practice-scripts/scm";

const REVIEW_LEAD_LIMIT = 200;

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
		const record = await work.reviewRecord();
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
				`Merged${merge.mergedBy === undefined ? "" : ` by ${merge.mergedBy}`}${merge.mergedByIsAuthor ? " (the author)" : ""}${merge.mergedAt === undefined ? "" : ` at ${merge.mergedAt}`}; ${String(decisions.length)} captured decision(s), ${String(approvalsBeforeMergeByOthers)} of them an undismissed APPROVED before the merge by an account other than the author's that is not marked bot.`,
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
		const leads = [mergeLead(merge, LABELS.merge), ...aggregate, ...rows, ...last];
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

/** One lead per submitted decision, oldest first, placed against the merge and the author. */
function decisionLeads(
	decisions: readonly ReviewDecision[],
	merge: MergeFacts,
	label: string,
): ReviewLead[] {
	return decisions.map((d) => ({
		at: { record: "review_threads.json" },
		label,
		facts: {
			state: d.state,
			author: d.author ?? "",
			submittedAt: d.submittedAt ?? "",
			...(merge.mergedAt !== undefined && d.submittedAt !== undefined
				? { beforeMerge: later(merge.mergedAt, d.submittedAt) }
				: {}),
			isAuthor: d.author !== undefined && d.author === merge.author,
			dismissed: d.dismissed === true,
			bot: d.bot,
		},
	}));
}

/** Each reviewer's last decision, only where all their decision times are known. */
function lastDecisionPerReviewer(decisions: readonly ReviewDecision[]): ReviewDecision[] {
	const last = new Map<string, ReviewDecision>();
	const unknown = new Set<string>();
	for (const d of decisions) {
		const reviewer = d.author ?? "";
		if (d.submittedAt === undefined) {
			unknown.add(reviewer);
		}
		last.set(reviewer, d);
	}
	return [...last.entries()]
		.filter(([reviewer]) => !unknown.has(reviewer))
		.map(([, decision]) => decision);
}

const present = (value: string | null | undefined) =>
	value === null || value === undefined || value === "" ? undefined : value;
