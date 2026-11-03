import { definePracticeScript } from "@hephaestus/practice-scripts";
import { checkableItems, issueShape, scm, subIssueRollup } from "@hephaestus/practice-scripts/scm";

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.issue");
		const shape = issueShape(work.item);
		const { body, labels, emptyOrTitleEcho } = shape;
		const checkboxes = checkableItems(body).length;
		const issueMentions = new Set((body.match(/(?:^|\s)#\d+\b/gu) ?? []).map((s) => s.trim())).size;
		const rollup = subIssueRollup(work.item);
		return ctx.review({
			leads: [],
			facts: {
				bodyLength: body.length,
				emptyOrTitleEcho: emptyOrTitleEcho ? 1 : 0,
				checkboxes,
				childIssueRefs: issueMentions,
				...rollup.metrics,
			},
			directions: [
				...shape.bodyDirections,
				`Captured: ${String(checkboxes)} task-list item(s), ${String(issueMentions)} issue-number mention(s); labels ${labels.join(", ") || "none"}.`,
				`Captured: ${rollup.text}.`,
				"Judge the standard once over the title, whole body and captured discussion; these counts locate text and decide nothing.",
			],
		});
	},
});
