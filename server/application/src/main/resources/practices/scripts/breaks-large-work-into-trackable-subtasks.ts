import { definePracticeScript, listWithin } from "@hephaestus/practice-scripts";
import { checkableItems, issueShape, scm, subIssueRollup } from "@hephaestus/practice-scripts/scm";

const DIRECTION_CHARS = 300;

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.issue");
		const shape = issueShape(work.item);
		const { body, labels } = shape;
		const items = checkableItems(body);
		const checked = items.filter((item) => item.checked).length;
		const issueMentions = new Set((body.match(/(?:^|\s)#\d+\b/gu) ?? []).map((s) => s.trim())).size;
		const rollup = subIssueRollup(work.item);

		return ctx.review({
			leads: [],
			facts: {
				bodyLength: body.length,
				taskCheckboxes: items.length,
				childIssueRefs: issueMentions,
				...rollup.metrics,
			},
			directions: [
				...shape.bodyDirections,
				`Captured: ${String(items.length)} task-list item(s) (${String(checked)} ticked), ${String(issueMentions)} issue-number mention(s); labels ${listWithin(labels, DIRECTION_CHARS - 120) || "none"}.`,
				`Sub-issues: ${rollup.text}.`,
				"Judge the standard once: whether the work is large and whether these items or mentions break it down is read from what they say, never from how many there are.",
			],
		});
	},
});
