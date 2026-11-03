import { definePracticeScript } from "@hephaestus/practice-scripts";
import { issueShape, scm } from "@hephaestus/practice-scripts/scm";

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.issue");
		const shape = issueShape(work.item);
		const { body, issueType, labels, emptyOrTitleEcho, hasDeliverableType, looksUmbrella } = shape;
		return ctx.review({
			leads: [],
			facts: {
				bodyLength: body.length,
				labelCount: labels.length,
				emptyOrTitleEcho: emptyOrTitleEcho ? 1 : 0,
				hasDeliverableType: hasDeliverableType ? 1 : 0,
				looksUmbrella: looksUmbrella ? 1 : 0,
			},
			directions: [
				...shape.bodyDirections,
				`Captured: ${String(body.length)} body character(s); labels ${labels.join(", ") || "none"}; native type ${issueType || "none"}.`,
				"Judge the standard once over the title, the whole body, the comments and any materialised document the issue delegates to.",
			],
		});
	},
});
