import { definePracticeScript } from "@hephaestus/practice-scripts/models";

export default definePracticeScript({
	meta: { models: { decision: "required" } },
	run: async (ctx) => ctx.review(),
});
