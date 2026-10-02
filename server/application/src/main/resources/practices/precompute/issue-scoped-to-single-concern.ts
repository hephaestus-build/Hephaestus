// Precompute FACTS for issue-scoped-to-single-concern: the task-list items and issue-number mentions the
// body carries, the provider's sub-issue rollup as reported, and how many sibling issues the inventory
// lists. Whether the issue is one concern at its level is the criteria's to decide.
import { readProjectInventory } from "../lib/context.ts";
import {
	bodyFact,
	classifyIssue,
	subIssueRollup,
	type IssueMetadata,
} from "../lib/issue-classification.ts";
import { checkableItems } from "../lib/review.ts";
import type { Hint } from "../lib/types.ts";

export default async function issueScopedToSingleConcern(
	_repo: string,
	_diff: Map<string, unknown>,
	m: IssueMetadata,
	contextDir?: string,
) {
	const shape = classifyIssue(m);
	const { body, labels, emptyOrTitleEcho } = shape;
	const checkboxes = checkableItems(body).length;
	const issueMentions = new Set((body.match(/(?:^|\s)#\d+\b/gu) ?? []).map((s) => s.trim())).size;
	const rollup = subIssueRollup(m);
	const inventory = await readProjectInventory(contextDir);

	const directions = [
		...bodyFact(shape),
		`Captured: ${String(checkboxes)} task-list item(s), ${String(issueMentions)} issue-number mention(s), ${rollup.text}; labels ${labels.join(", ") || "none"}. Judge the standard once over the title and the whole body; these counts locate text and decide nothing.`,
		inventory === null
			? "project_inventory.json was not captured."
			: `project_inventory.json lists ${String(inventory.issues?.length ?? 0)} issue(s)${inventory.truncated === true ? " (a truncated listing)" : ""}; compare titles and bodies, never the count.`,
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
			siblingIssueCount: inventory?.issues?.length ?? 0,
		},
		directions,
	};
}
