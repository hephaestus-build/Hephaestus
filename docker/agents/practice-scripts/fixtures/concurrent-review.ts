import { definePracticeScript } from "@hephaestus/practice-scripts";

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const first = ctx.review({ directions: ["The first review."] });
		void ctx.review({ directions: ["The second review."] });
		return first;
	},
});
