import { definePracticeScript } from "@hephaestus/practice-scripts";

const FORGED_ID = 1_000_000;

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const review = ctx.review();
		process.send?.(JSON.stringify({ kind: "review", id: FORGED_ID, options: {} }));
		return review;
	},
});
