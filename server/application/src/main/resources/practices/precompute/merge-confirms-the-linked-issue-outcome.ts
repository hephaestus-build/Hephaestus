// Precompute FACTS for merge-confirms-the-linked-issue-outcome: every linked issue with a checkable
// outcome, one row each — its task-list items as captured, ticked and unticked, its state, and how
// the change names it. The practice's occasion is any linked item that states a checkable outcome,
// not only the one the body closes; the review decides whether the merge left each confirmed.
import {
	branchIssueReferences,
	closingReferences,
	issueNumberReferences,
} from "../lib/references.ts";
import { checkableItems, readLinkedWorkItems } from "../lib/review.ts";
import type { DiffFile, Hint, PullRequestMetadata } from "../lib/types.ts";

/** Headings under which an issue states what "done" means without a task list. */
const OUTCOME_HEADING =
	/^\s*#+\s*(?:acceptance criteria|definition of done|dod|expected (?:outcome|result|behaviou?r)|given\b)/imu;

function text(value: unknown): string {
	return typeof value === "string" ? value : "";
}

/** How the change names a linked issue, strongest form first. */
function howLinked(
	number: number,
	closing: ReadonlySet<number>,
	named: ReadonlySet<number>,
): string {
	if (closing.has(number)) {
		return "closed by the body";
	}
	return named.has(number)
		? "named by the title, body or branch"
		: "linked through a commit or another item";
}

export default async function mergeConfirmsTheLinkedIssueOutcome(
	_repoPath: string,
	_diffFiles: Map<string, DiffFile>,
	metadata: PullRequestMetadata,
	contextDir?: string,
) {
	const body = text(metadata.body);
	const closing = new Set(closingReferences(body));
	const named = new Set([
		...issueNumberReferences(text(metadata.title)),
		...issueNumberReferences(body),
		...branchIssueReferences(metadata.source_branch),
	]);
	const linked = await readLinkedWorkItems(contextDir);
	const hints: Hint[] = [];
	for (const item of linked) {
		const items = checkableItems(item.body);
		const ticked = items.filter((i) => i.checked).length;
		const heading = OUTCOME_HEADING.test(item.body);
		const subIssues = item.subIssuesTotal ?? 0;
		const checkable = items.length > 0 || heading || subIssues > 0;
		const how = howLinked(item.number, closing, named);
		hints.push({
			file: `inputs/context/linked_work_items/${String(item.number)}.md`,
			line: items[0]?.line ?? 0,
			pattern: checkable ? "checkable outcome" : "no checkable outcome",
			context: `#${String(item.number)} ${item.title} — ${how}; ${String(items.length)} task-list item(s), ${String(ticked)} ticked${heading ? "; an outcome heading" : ""}${subIssues > 0 ? `; ${String(item.subIssuesCompleted ?? 0)}/${String(subIssues)} sub-issues done` : ""}; state ${item.state ?? "unknown"}`,
			inDiff: false,
			flags: {
				number: item.number,
				how,
				taskItems: items.length,
				ticked,
				unticked: items.length - ticked,
				outcomeHeading: heading,
				subIssuesTotal: subIssues,
				state: item.state ?? "",
			},
		});
	}
	const occasions = hints.filter((h) => h.pattern === "checkable outcome");
	const directions: string[] = [];
	if (metadata.state !== "MERGED") {
		directions.push("The change is not merged; the practice's occasion is the merge.");
	} else if (occasions.length === 0) {
		directions.push(
			linked.length === 0
				? "No linked issue was captured; there is no stated outcome to confirm."
				: "No linked issue states a checkable outcome (a task list, an acceptance or Definition-of-Done heading, sub-issues): the occasion did not arise, whatever the description says.",
		);
	} else {
		directions.push(
			`${String(occasions.length)} linked issue(s) state a checkable outcome — every one is an occasion, the one the body closes and the ones it only names alike. For each, the confirmation is in the record: its items ticked as captured, or the description or a closing comment naming which items are done and where the rest moves. Work delivered in the diff but neither ticked nor named is unconfirmed.`,
		);
	}
	return {
		hints,
		metrics: {
			linkedItems: linked.length,
			checkableItems: occasions.length,
			untickedItems: occasions.reduce((sum, h) => sum + Number(h.flags.unticked), 0),
		},
		directions,
	};
}
