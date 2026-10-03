// Precompute FACTS for issue-states-an-actionable-problem: what the captured issue carries — whether
// its body is empty or only repeats the title, its labels and its native type. Whether a maintainer
// can picture the work and start is the criteria's to decide from the whole text.
import { bodyFact, classifyIssue, type IssueMetadata } from "../lib/issue-classification.ts";
import type { Hint } from "../lib/types.ts";

export default function issueStatesAnActionableProblem(
	_repo: string,
	_diff: Map<string, unknown>,
	m: IssueMetadata,
) {
	const shape = classifyIssue(m);
	const { body, issueType, labels, emptyOrTitleEcho, hasDeliverableType, looksUmbrella } = shape;

	const directions = [
		...bodyFact(shape),
		`Captured: ${String(body.length)} body character(s); labels ${labels.join(", ") || "none"}; native type ${issueType || "none"}. Judge the standard once over the title, the whole body, the comments and any materialised document the issue delegates to.`,
	];

	const hints: Hint[] = [];
	return {
		hints,
		metrics: {
			bodyLength: body.length,
			labelCount: labels.length,
			emptyOrTitleEcho: emptyOrTitleEcho ? 1 : 0,
			hasDeliverableType: hasDeliverableType ? 1 : 0,
			looksUmbrella: looksUmbrella ? 1 : 0,
		},
		directions,
	};
}
