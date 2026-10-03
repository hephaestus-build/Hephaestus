// Precompute FACTS for breaks-large-work-into-trackable-subtasks: the task-list items and issue-number
// mentions the body carries, its labels, and the provider's sub-issue rollup as reported. Whether the
// work is large and whether it is broken down are the criteria's to decide from the text.
import {
	bodyFact,
	classifyIssue,
	subIssueRollup,
	type IssueMetadata,
} from "../lib/issue-classification.ts";
import { checkableItems } from "../lib/review.ts";
import type { Hint } from "../lib/types.ts";

export default function breaksLargeWorkIntoTrackableSubtasks(
	_repo: string,
	_diff: Map<string, unknown>,
	m: IssueMetadata,
) {
	const shape = classifyIssue(m);
	const { body, labels } = shape;
	const items = checkableItems(body);
	const checked = items.filter((item) => item.checked).length;
	const issueMentions = new Set((body.match(/(?:^|\s)#\d+\b/gu) ?? []).map((s) => s.trim())).size;
	const rollup = subIssueRollup(m);

	const directions = [
		...bodyFact(shape),
		`Captured: ${String(items.length)} task-list item(s) (${String(checked)} ticked), ${String(issueMentions)} issue-number mention(s), ${rollup.text}; labels ${labels.join(", ") || "none"}. Judge the standard once: whether the work is large and whether these items or mentions break it down is read from what they say, never from how many there are.`,
	];

	const hints: Hint[] = [];
	return {
		hints,
		metrics: {
			bodyLength: body.length,
			taskCheckboxes: items.length,
			childIssueRefs: issueMentions,
			...rollup.metrics,
		},
		directions,
	};
}
