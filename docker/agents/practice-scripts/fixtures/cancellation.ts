import { definePracticeScript } from "@hephaestus/practice-scripts";

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		return ctx.review();
	},
});
