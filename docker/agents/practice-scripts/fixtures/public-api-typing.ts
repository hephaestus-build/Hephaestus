import { definePracticeScript } from "@hephaestus/practice-scripts";

/** What the public API does not offer. `tsc` fails if one of these starts to compile. */
export default definePracticeScript({
	meta: {},
	async run(ctx) {
		// @ts-expect-error A direct model is not part of the public API.
		void ctx.models;
		// @ts-expect-error The attempt's budget is not part of the public API.
		void ctx.budget;
		// @ts-expect-error An earlier result is not part of the public API.
		void ctx.previous;
		// @ts-expect-error Evidence tools expose AI SDK types.
		void ctx.evidence.tools;
		const reviewed = await ctx.review();
		// @ts-expect-error A review ends the script, so its result can never be read.
		void reviewed.summary;
		return reviewed;
	},
});
