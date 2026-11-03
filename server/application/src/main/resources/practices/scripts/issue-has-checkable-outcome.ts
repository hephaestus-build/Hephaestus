import { definePracticeScript } from "@hephaestus/practice-scripts";
import { checkableItems, issueShape, scm } from "@hephaestus/practice-scripts/scm";

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.issue");
		const shape = issueShape(work.item);
		const { body, issueType, labels, emptyOrTitleEcho, hasDeliverableType, looksUmbrella } = shape;
		const items = checkableItems(body);
		const checkedBoxes = items.filter((item) => item.checked).length;
		return ctx.review({
			leads: [],
			facts: {
				bodyLength: body.length,
				taskCheckboxes: items.length,
				uncheckedBoxes: items.length - checkedBoxes,
				checkedBoxes,
				emptyOrTitleEcho: emptyOrTitleEcho ? 1 : 0,
				hasDeliverableType: hasDeliverableType ? 1 : 0,
				looksUmbrella: looksUmbrella ? 1 : 0,
			},
			directions: [
				...shape.bodyDirections,
				`Captured: ${String(items.length)} task-list item(s) in the body (${String(checkedBoxes)} ticked); labels ${labels.join(", ") || "none"}; native type ${issueType || "none"}.`,
				"Judge the standard once over the title, the whole body, the comments and any materialised document; these counts locate text and decide nothing.",
			],
		});
	},
});
