import { definePracticeScript, listWithin } from "@hephaestus/practice-scripts";
import { scm } from "@hephaestus/practice-scripts/scm";

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.issue");
		const { labels, assignees, milestone } = work.item;
		return ctx.review({
			leads: [],
			facts: {
				labelCount: labels.length,
				assigneeCount: assignees.length,
				hasMilestone: milestone === null ? 0 : 1,
			},
			directions: [
				`Captured labels: ${listWithin(labels, 180) || "none"}; assignees: ${listWithin(assignees, 100) || "none"}; milestone: ${milestone ?? "none recorded"}.`,
				"These are presence facts from the capture, not proof that a label is useful or correct, that an assignee is the right owner, or that the routing metadata was the same at an earlier time.",
				"Judge the authored routing standard over the issue and its captured context. Distinguish a substantive work item from a discussion or process placeholder; do not prescribe a house taxonomy or turn missing evidence into an empty routing set.",
			],
		});
	},
});
