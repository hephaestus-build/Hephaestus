// Precompute FACTS for issue-has-checkable-outcome: the task-list items the body carries and how the
// issue is labelled and typed. Whether any of the text is a finish line is the criteria's to decide.
import { bodyFact, classifyIssue, type IssueMetadata } from "../lib/issue-classification.ts";
import { checkableItems } from "../lib/review.ts";
import type { Hint } from "../lib/types.ts";

export default function issueHasCheckableOutcome(
	_repo: string,
	_diff: Map<string, unknown>,
	m: IssueMetadata,
) {
	const shape = classifyIssue(m);
	const { body, issueType, labels, emptyOrTitleEcho, hasDeliverableType, looksUmbrella } = shape;
	const items = checkableItems(body);
	const checkedBoxes = items.filter((item) => item.checked).length;

	const directions = [
		...bodyFact(shape),
		`Captured: ${String(items.length)} task-list item(s) in the body (${String(checkedBoxes)} ticked); labels ${labels.join(", ") || "none"}; native type ${issueType || "none"}. Judge the standard once over the title, the whole body, the comments and any materialised document; these counts locate text and decide nothing.`,
	];

	const hints: Hint[] = [];
	return {
		hints,
		metrics: {
			bodyLength: body.length,
			taskCheckboxes: items.length,
			uncheckedBoxes: items.length - checkedBoxes,
			checkedBoxes,
			emptyOrTitleEcho: emptyOrTitleEcho ? 1 : 0,
			hasDeliverableType: hasDeliverableType ? 1 : 0,
			looksUmbrella: looksUmbrella ? 1 : 0,
		},
		directions,
	};
}
