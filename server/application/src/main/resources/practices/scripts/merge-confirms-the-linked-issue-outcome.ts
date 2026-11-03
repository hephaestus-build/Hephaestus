import {
	definePracticeScript,
	type ReviewLead,
	type PracticeScriptContext,
} from "@hephaestus/practice-scripts";
import {
	branchIssueReferences,
	checkableItems,
	issueReferences,
	scm,
	subIssueRollup,
} from "@hephaestus/practice-scripts/scm";

const REVIEW_LEAD_LIMIT = 200;

export const LABELS = {
	"checkable-outcome":
		"A linked issue with a task list, an outcome heading or a positive sub-issue count.",
	"no-checkable-outcome":
		"A linked issue with no task list, no outcome heading and a sub-issue count of zero.",
	"no-task-list-or-outcome-heading":
		"A linked issue with no task list, no outcome heading, and no count that shows it has no sub-issues.",
};
type OutcomeKind = keyof typeof LABELS;

function howLinked(
	number: number,
	closing: ReadonlySet<number>,
	named: ReadonlySet<number>,
): string {
	if (closing.has(number)) {
		return "named with a closing keyword in the body";
	}
	return named.has(number)
		? "named by the title, body or branch"
		: "linked through a commit or another item";
}

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
		const { item: record } = work;
		const body = record.body ?? "";
		const closing = new Set(issueReferences(body).closing);
		const named = new Set([
			...issueReferences(record.title ?? "").mentioned,
			...issueReferences(body).mentioned,
			...branchIssueReferences(record.source_branch),
		]);
		const capture = await work.linkedIssues();
		const captured = capture?.items ?? null;
		const linked = captured ?? [];
		const leads: ReviewLead[] = [];
		let untickedItems = 0;
		for (const item of linked) {
			const items = checkableItems(item.body);
			const ticked = items.filter((i) => i.checked).length;
			const heading = OUTCOME_HEADING.test(item.body);
			const rollup = subIssueRollup({
				sub_issues_total: item.subIssuesTotal,
				sub_issues_completed: item.subIssuesCompleted,
				sub_issues_source: item.subIssuesSource,
			});
			const subIssues = rollup.total;
			const checkable = items.length > 0 || heading || (subIssues ?? 0) > 0;
			let kind: OutcomeKind = checkable ? "checkable-outcome" : "no-checkable-outcome";
			if (subIssues === undefined || (item.subIssuesSource === "children" && subIssues === 0)) {
				kind = checkable ? kind : "no-task-list-or-outcome-heading";
			}
			if (checkable) {
				untickedItems += items.length - ticked;
			}
			const how =
				item.how === "closesOnMerge"
					? "a provider closing candidate"
					: howLinked(item.number, closing, named);
			leads.push({
				at: { record: `linked_work_items/${String(item.number)}.md` },
				label: LABELS[kind],
				facts: {
					number: item.number,
					how,
					taskItems: items.length,
					ticked,
					unticked: items.length - ticked,
					outcomeHeading: heading,
					...(subIssues === undefined ? {} : { subIssuesTotal: subIssues }),
					...(rollup.completed === undefined ? {} : { subIssuesCompleted: rollup.completed }),
					subIssuesSource: item.subIssuesSource ?? "",
					state: item.state ?? "",
				},
			});
		}
		const candidates = leads.filter((lead) => lead.label === LABELS["checkable-outcome"]);
		const directions: string[] = [];
		if (captured === null) {
			directions.push(
				"linked_work_items.json was not captured: a collection gap, not a change without a linked issue.",
			);
		} else if (candidates.length === 0) {
			directions.push(
				linked.length === 0
					? "The capture holds no linked issue; there is no stated outcome to confirm."
					: "No captured linked issue carries a task list, an acceptance or Definition-of-Done heading or a positive captured sub-issue count; a rollup the provider does not report says nothing about sub-issues.",
			);
		} else {
			directions.push(
				`${String(candidates.length)} captured linked issue(s) state a checkable outcome. Each is a candidate: the criteria decide which of them this change implements, from the provider's closing link and the author's own words; a mention alone asks nothing of it.`,
				"For an issue it implements, assess confirmation in the captured record: its items ticked as captured, or the description or a closing comment naming which items are done and where the rest moves.",
				"Work delivered in the diff but neither ticked nor named is unconfirmed. A confirmation captured after the merge counts; when it was written is unknown unless independently dated evidence establishes it.",
			);
		}
		if (leads.some((lead) => lead.facts?.subIssuesSource === "children")) {
			directions.push(
				"subIssuesSource=children counts only the child issues stored at capture. That mirror may omit other children, so all of them closed does not show that the whole hierarchy is complete.",
			);
		}
		return ctx.review({
			leads: leads.slice(0, REVIEW_LEAD_LIMIT),
			limitations:
				leads.length > REVIEW_LEAD_LIMIT
					? [
							{
								what: "linked issue leads",
								count: leads.length - REVIEW_LEAD_LIMIT,
								reason: "selection",
							},
						]
					: [],
			facts: {
				linkedItems: linked.length,
				checkableItems: candidates.length,
				untickedItems,
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

/** A heading under which an issue states what "done" means without a task list. */
const OUTCOME_HEADING =
	/^\s*#+\s*(?:acceptance criteria|definition of done|dod\b|done when|expected (?:outcome|result|behaviou?r)|given\b)/imu;
