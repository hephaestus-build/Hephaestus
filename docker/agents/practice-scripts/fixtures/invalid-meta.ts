import { definePracticeScript } from "@hephaestus/practice-scripts/models";

export default definePracticeScript({
	// @ts-expect-error -- "sometimes" is not a need, which is the point of this fixture.
	meta: { models: { decision: "sometimes" } },
	run: async (ctx) => ctx.review(),
});
