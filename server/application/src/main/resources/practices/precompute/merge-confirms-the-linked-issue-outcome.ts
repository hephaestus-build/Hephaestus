import { contextFile } from "../lib/context.ts";
import { text } from "../lib/practice-contract.ts";
// Precompute FACTS for merge-confirms-the-linked-issue-outcome: every captured linked issue, one row
// each — its task-list items as captured, ticked and unticked, its state, and how the change names it.
// Which of them this change implements is the criteria's to decide; a row is a candidate, not an
// occasion.
import {
	branchIssueReferences,
	closingReferences,
	issueNumberReferences,
} from "../lib/references.ts";
import { checkableItems, mergeFacts, readLinkedWorkItems } from "../lib/review.ts";
import type { DiffFile, Hint, PullRequestMetadata } from "../lib/types.ts";

/** Headings under which an issue states what "done" means without a task list. */
const OUTCOME_HEADING =
	/^\s*#+\s*(?:acceptance criteria|definition of done|dod|expected (?:outcome|result|behaviou?r)|given\b)/imu;

/** How the change names a linked issue, strongest form first. */
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

export default async function mergeConfirmsTheLinkedIssueOutcome(
	_repoPath: string,
	_diffFiles: Map<string, DiffFile>,
	metadata: PullRequestMetadata,
	contextDir: string | undefined,
	_changeDir: string | undefined,
	contextReference: string,
) {
	const body = text(metadata.body);
	const closing = new Set(closingReferences(body));
	const named = new Set([
		...issueNumberReferences(text(metadata.title)),
		...issueNumberReferences(body),
		...branchIssueReferences(metadata.source_branch),
	]);
	const captured = await readLinkedWorkItems(contextDir);
	const linked = captured ?? [];
	const hints: Hint[] = [];
	for (const item of linked) {
		const items = checkableItems(item.body);
		const ticked = items.filter((i) => i.checked).length;
		const heading = OUTCOME_HEADING.test(item.body);
		const subIssues = item.subIssuesTotal;
		const checkable = items.length > 0 || heading || (subIssues ?? 0) > 0;
		let rollup = "";
		let pattern = checkable ? "checkable outcome" : "no checkable outcome";
		if (subIssues === undefined) {
			rollup = "; sub-issue rollup not reported";
			pattern = checkable ? pattern : "no task list or outcome heading";
		} else if (subIssues > 0) {
			rollup =
				item.subIssuesCompleted === undefined
					? `; ${String(subIssues)} sub-issues, completed count not reported`
					: `; ${String(item.subIssuesCompleted)}/${String(subIssues)} sub-issues done`;
		}
		const rollupFlags: Hint["flags"] = subIssues === undefined ? {} : { subIssuesTotal: subIssues };
		// The provider's own link outranks what the text says: a link made in the UI matches no `#N`.
		const how =
			item.how === "closesOnMerge"
				? "a provider closing candidate"
				: howLinked(item.number, closing, named);
		hints.push({
			file: contextFile(contextReference, `linked_work_items/${String(item.number)}.md`),
			line: items[0]?.line ?? 0,
			pattern,
			context: `#${String(item.number)} ${item.title} — ${how}; ${String(items.length)} task-list item(s), ${String(ticked)} ticked as captured${heading ? "; an outcome heading" : ""}${rollup}; state ${item.state ?? "unknown"}`,
			inDiff: false,
			flags: {
				number: item.number,
				how,
				taskItems: items.length,
				ticked,
				unticked: items.length - ticked,
				outcomeHeading: heading,
				...rollupFlags,
				state: item.state ?? "",
			},
		});
	}
	const candidates = hints.filter((h) => h.pattern === "checkable outcome");
	const directions: string[] = [];
	if (!mergeFacts(metadata).merged) {
		directions.push("The change is not merged; the practice's occasion is the merge.");
	} else if (captured === null) {
		directions.push(
			"linked_work_items.json was not captured: a collection gap, not a change without a linked issue.",
		);
	} else if (candidates.length === 0) {
		directions.push(
			linked.length === 0
				? "The capture holds no linked issue; there is no stated outcome to confirm."
				: "No captured linked issue carries a task list, an acceptance or Definition-of-Done heading or a reported sub-issue rollup; a rollup the provider does not report says nothing about sub-issues.",
		);
	} else {
		directions.push(
			`${String(candidates.length)} captured linked issue(s) state a checkable outcome. Each is a candidate: the criteria decide which of them this change implements, from the provider's closing link and the author's own words; a mention alone asks nothing of it. For an issue it implements, assess confirmation in the captured record: its items ticked as captured, or the description or a closing comment naming which items are done and where the rest moves. Work delivered in the diff but neither ticked nor named is unconfirmed. A confirmation captured after the merge counts; when it was written is unknown unless independently dated evidence establishes it.`,
		);
	}
	return {
		hints,
		metrics: {
			linkedItems: linked.length,
			checkableItems: candidates.length,
			untickedItems: candidates.reduce((sum, h) => sum + Number(h.flags.unticked), 0),
		},
		directions,
	};
}
