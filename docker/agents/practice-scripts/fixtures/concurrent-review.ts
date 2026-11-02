import { definePracticeScript } from "../lib/core/practice-script.ts";

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const first = ctx.review({ directions: ["The first review."] });
		void ctx.review({ directions: ["The second review."] });
		return first;
	},
});
