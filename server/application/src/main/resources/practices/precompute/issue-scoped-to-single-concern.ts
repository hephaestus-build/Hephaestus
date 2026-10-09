// Precompute FACTS for issue-scoped-to-single-concern: the task-list items and issue-number mentions the
// body carries and the provider's sub-issue rollup as reported. Whether the issue is one concern at its level
// is the criteria's to decide.
import {
	bodyFact,
	classifyIssue,
	subIssueRollup,
	type IssueMetadata,
} from "../lib/issue-classification.ts";
import { checkableItems } from "../lib/review.ts";
import type { Hint } from "../lib/types.ts";

export default function issueScopedToSingleConcern(
	_repo: string,
	_diff: Map<string, unknown>,
	m: IssueMetadata,
) {
	const shape = classifyIssue(m);
	const { body, labels, emptyOrTitleEcho } = shape;
	const checkboxes = checkableItems(body).length;
	const issueMentions = new Set((body.match(/(?:^|\s)#\d+\b/gu) ?? []).map((s) => s.trim())).size;
	const rollup = subIssueRollup(m);
	const directions = [
		...bodyFact(shape),
		`Captured: ${String(checkboxes)} task-list item(s), ${String(issueMentions)} issue-number mention(s), ${rollup.text}; labels ${labels.join(", ") || "none"}. Judge the standard once over the title, whole body and captured discussion; these counts locate text and decide nothing.`,
	];

	const hints: Hint[] = [];
	return {
		hints,
		metrics: {
			bodyLength: body.length,
			emptyOrTitleEcho: emptyOrTitleEcho ? 1 : 0,
			checkboxes,
			childIssueRefs: issueMentions,
			...rollup.metrics,
		},
		directions,
	};
}
